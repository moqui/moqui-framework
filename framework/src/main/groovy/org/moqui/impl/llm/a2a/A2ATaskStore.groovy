/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 *
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 *
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.impl.llm.a2a

import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityConditionFactory
import org.moqui.entity.EntityFind
import org.moqui.entity.EntityList
import org.moqui.entity.EntityValue
import org.moqui.impl.llm.LlmConversationImpl

import java.nio.charset.StandardCharsets
import java.sql.Timestamp
import java.time.Instant

/**
 * Owner-scoped persistence for the A2A model: contexts, tasks, status history, messages, artifacts, the event
 * log and push configurations, plus the mapping of those rows to A2A 1.0 wire objects. Every public operation
 * runs in its own short transaction; no LLM calls, no JSON-RPC, no HTTP.
 */
final class A2ATaskStore {
    private static Map<String, Object> listTasksTx(ExecutionContext ec, Map<String, Object> request) {
        String userId = requireUser(ec)
        int requestedPageSize = A2ATypes.pageSize(request.pageSize)
        int requestedHistoryLength = A2ATypes.historyLength(request.historyLength)
        Map<String, Object> cursor = decodeCursor(request.pageToken as String)
        String requestedStatusId = request.status != null ? A2ATypes.stateId(ec, request.status) : null
        Timestamp afterTimestamp = request.statusTimestampAfter as Timestamp
        // filter, sort, and page in the database over task + current status (see A2ATaskAndStatus)
        Closure<EntityFind> filteredFind = {
            EntityFind find = ec.entity.find('moqui.a2a.A2ATaskAndStatus').condition('userId', userId).useCache(false)
            if (request.contextId != null) find.condition('contextId', request.contextId)
            if (requestedStatusId != null) find.condition('statusId', requestedStatusId)
            if (afterTimestamp != null) find.condition('statusTimestamp', EntityCondition.GREATER_THAN_EQUAL_TO, afterTimestamp)
            find
        }
        long totalSize = disabled(ec) { filteredFind.call().count() }
        EntityList rows = disabled(ec) {
            EntityFind find = filteredFind.call()
            if (cursor) {
                EntityConditionFactory ecf = ec.entity.conditionFactory
                Timestamp cursorDate = Timestamp.from(Instant.parse(cursor.statusDate as String))
                find.condition(ecf.makeCondition([
                    ecf.makeCondition('statusTimestamp', EntityCondition.LESS_THAN, cursorDate),
                    ecf.makeCondition([ecf.makeCondition('statusTimestamp', EntityCondition.EQUALS, cursorDate),
                        ecf.makeCondition('taskId', EntityCondition.LESS_THAN, cursor.taskId)], EntityCondition.AND)
                ], EntityCondition.OR))
            }
            find.orderBy(['-statusTimestamp', '-taskId']).limit(requestedPageSize + 1).list()
        }
        boolean hasMore = rows.size() > requestedPageSize
        List<EntityValue> pageRows = rows.take(requestedPageSize) as List<EntityValue>
        [tasks: pageRows.collect { EntityValue taskRow ->
            taskMap(ec, taskRow, requestedHistoryLength, request.includeArtifacts == true)
        }, nextPageToken: hasMore ? encodeCursor(pageRows.last()) : '', pageSize: requestedPageSize,
         totalSize: totalSize] as Map<String, Object>
    }

    private static Map<String, Object> addArtifactTx(ExecutionContext ec, Map<String, Object> request) {
        EntityValue task = ownedTask(ec, request.taskId as String, true)
        if (terminal(ec, task)) throw new IllegalStateException("task ${task.taskId} is terminal")
        if (!(request.artifact instanceof Map)) throw new IllegalArgumentException('artifact must be an object')
        Map<String, Object> artifact = new LinkedHashMap<>((Map<String, Object>) request.artifact)
        String artifactId = A2ATypes.text(artifact.artifactId)
        if (artifactId == null) throw new IllegalArgumentException('artifact.artifactId is required')
        List<Map<String, Object>> parts = A2ATypes.requireParts(artifact.parts)
        A2ATypes.requireStringList(artifact.extensions, 'artifact.extensions')
        A2ATypes.requireMap(artifact.metadata, 'artifact.metadata')
        A2ATypes.requireMap(request.metadata, 'metadata')
        boolean append = request.append == true
        boolean lastChunk = request.lastChunk == true

        // artifactId is unique only within its task (A2A Artifact.artifact_id), so the key is taskId + artifactId
        EntityValue artifactValue = disabled(ec) {
            ec.entity.find('moqui.a2a.A2AArtifact').condition([taskId: task.taskId, artifactId: artifactId]).one()
        }
        disabled(ec) {
            if (artifactValue == null) {
                artifactValue = ec.entity.makeValue('moqui.a2a.A2AArtifact')
                    .setAll([taskId: task.taskId, artifactId: artifactId])
            } else if (!append) {
                ec.entity.find('moqui.a2a.A2AArtifactPart')
                    .condition([taskId: task.taskId, artifactId: artifactId]).deleteAll()
            }
            artifactValue.setAll([name: artifact.name ?: artifactValue.name,
                description: artifact.description ?: artifactValue.description,
                extensionsJson: A2ATypes.toJson(artifact.extensions), metadataJson: A2ATypes.toJson(artifact.metadata),
                contentLocation: artifact.contentLocation])
            artifactValue.createOrUpdate()
            persistArtifactParts(ec, task.taskId as String, artifactId, parts, append)
        }
        // the event carries only this chunk (TaskArtifactUpdateEvent); the stored artifact holds all parts
        Map<String, Object> chunk = A2ATypes.compact([artifactId: artifactId, name: artifactValue.name,
            description: artifactValue.description, parts: parts, metadata: artifact.metadata,
            extensions: artifact.extensions])
        appendTaskEvent(ec, task, A2ATypes.EVENT_ARTIFACT, null, artifactId, null,
            [artifactUpdate: A2ATypes.compact([taskId: task.taskId, contextId: task.contextId, artifact: chunk,
                append: append, lastChunk: lastChunk, metadata: request.metadata])] as Map<String, Object>,
            append, lastChunk)
        [artifact: artifactMap(ec, artifactValue)]
    }

    /** Snapshot of an owned task plus its persisted StreamResponse events after afterOrdinal. */
    private static Map<String, Object> subscribeTaskTx(ExecutionContext ec, Map<String, Object> request) {
        EntityValue task = ownedTask(ec, request.taskId as String)
        long afterSequenceNum = request.afterOrdinal != null ? request.afterOrdinal as long : -1L
        EntityList rows = disabled(ec) {
            ec.entity.find('moqui.a2a.A2ATaskEvent').condition('taskId', task.taskId)
                .condition('sequenceNum', EntityCondition.GREATER_THAN, afterSequenceNum)
                .orderBy(['sequenceNum', 'eventDate', 'taskEventId']).useCache(false).list()
        }
        List<Map<String, Object>> events = rows.collect { EntityValue eventRow ->
            A2ATypes.parseJson(eventRow.payloadJson as String) as Map<String, Object>
        }
        long lastSequenceNum = rows ? rows.last().sequenceNum as Long : afterSequenceNum
        [task: taskMap(ec, task, A2ATypes.historyLength(request.historyLength), request.includeArtifacts != false),
         events: events, lastOrdinal: lastSequenceNum] as Map<String, Object>
    }

    private static Map<String, Object> createPushConfigTx(ExecutionContext ec, Map<String, Object> request) {
        EntityValue task = ownedTask(ec, request.taskId as String)
        String url = A2ATypes.text(request.url)
        if (url == null) throw new IllegalArgumentException('url is required')
        A2ATypes.requirePushUrl(url)
        // authentication is optional in A2A PushNotificationConfig; when present it must name a scheme
        Map<String, Object> authentication = null
        if (request.authentication != null) {
            A2ATypes.requireMap(request.authentication, 'authentication')
            authentication = new LinkedHashMap<>((Map<String, Object>) request.authentication)
            if (A2ATypes.text(authentication.scheme) == null)
                throw new IllegalArgumentException('authentication.scheme is required when authentication is given')
        }
        EntityValue configValue = disabled(ec) {
            if (request.configId != null && ec.entity.find('moqui.a2a.A2APushConfig')
                    .condition('configId', request.configId).useCache(false).one() != null)
                throw new IllegalArgumentException('push notification configuration id already exists')
            EntityValue created = ec.entity.makeValue('moqui.a2a.A2APushConfig').setAll([
                configId: request.configId, taskId: task.taskId, url: url, token: request.token,
                authenticationJson: A2ATypes.toJson(authentication), failCount: 0])
            if (created.configId == null) created.setSequencedIdPrimary()
            created.create()
        }
        [config: pushConfigMap(configValue)]
    }

    private static Map<String, Object> getPushConfigTx(ExecutionContext ec, Map<String, Object> request) {
        EntityValue task = ownedTask(ec, request.taskId as String)
        EntityValue configValue = disabled(ec) {
            ec.entity.find('moqui.a2a.A2APushConfig')
                .condition([taskId: task.taskId, configId: request.configId]).one()
        }
        if (configValue == null) throw new IllegalArgumentException('push notification configuration not found')
        [config: pushConfigMap(configValue)]
    }

    private static Map<String, Object> listPushConfigsTx(ExecutionContext ec, Map<String, Object> request) {
        EntityValue task = ownedTask(ec, request.taskId as String)
        int requestedPageSize = A2ATypes.pageSize(request.pageSize)
        int offset = decodeOffset(request.pageToken as String)
        EntityList rows = disabled(ec) {
            ec.entity.find('moqui.a2a.A2APushConfig').condition('taskId', task.taskId)
                .orderBy('configId').offset(offset).limit(requestedPageSize + 1).list()
        }
        boolean hasMore = rows.size() > requestedPageSize
        if (hasMore) rows.remove(rows.size() - 1)
        [configs: rows.collect { EntityValue row -> pushConfigMap(row) },
         nextPageToken: hasMore ? encodeOffset(offset + rows.size()) : ''] as Map<String, Object>
    }

    private static Map<String, Object> deletePushConfigTx(ExecutionContext ec, Map<String, Object> request) {
        EntityValue task = ownedTask(ec, request.taskId as String)
        long count = disabled(ec) {
            ec.entity.find('moqui.a2a.A2APushConfig')
                .condition([taskId: task.taskId, configId: request.configId]).deleteAll()
        }
        if (count == 0L) throw new IllegalArgumentException('push notification configuration not found')
        [deleted: true]
    }

    static void saveResult(ExecutionContext ec, EntityValue message, EntityValue task, Map<String, Object> result) {
        isolated(ec) {
            EntityValue storedMessage = ec.entity.find('moqui.a2a.A2AMessage')
                .condition('a2aMessageId', message.a2aMessageId).one()
            EntityValue storedTask = ownedTask(ec, task.taskId as String, true)
            storedMessage.set('resultJson', A2ATypes.toJson(result)).update()
            storedTask.set('resultJson', A2ATypes.toJson(result)).update()
        }
    }

    static EntityValue appendAgentMessage(ExecutionContext ec, EntityValue task, Map<String, Object> message) {
        Map<String, Object> checked = A2ATypes.requireMessage(message, false)
        if (checked.role != 'ROLE_AGENT') throw new IllegalArgumentException('agent output requires ROLE_AGENT')
        checked.contextId = task.contextId
        checked.taskId = task.taskId
        isolated(ec) { persistMessage(ec, ownedTask(ec, task.taskId as String, true), checked) }
    }

    static Map<String, Object> taskMap(ExecutionContext ec, EntityValue task, int historyLength, boolean includeArtifacts) {
        Map<String, Object> wire = [
            id: task.taskId,
            contextId: task.contextId,
            status: statusMap(ec, currentStatusValue(ec, task))
        ]
        if (historyLength > 0) {
            EntityList history = disabled(ec) {
                ec.entity.find('moqui.a2a.A2AMessage').condition('taskId', task.taskId)
                    .orderBy('-sequenceNum').limit(historyLength).list()
            }
            wire.history = history.reverse().collect { EntityValue messageRow -> messageMap(ec, messageRow) }
        }
        if (includeArtifacts) {
            wire.artifacts = disabled(ec) {
                ec.entity.find('moqui.a2a.A2AArtifact').condition('taskId', task.taskId)
                    .orderBy('artifactId').list().collect { EntityValue artifactRow -> artifactMap(ec, artifactRow) }
            }
        }
        Object metadata = A2ATypes.parseJson(task.metadataJson as String)
        if (metadata != null) wire.metadata = metadata
        wire
    }

    /** A2AContext row for a context whose LLM conversation the executor has already created. */
    static EntityValue createContextRow(ExecutionContext ec, String contextId, Map<String, Object> metadata) {
        disabled(ec) {
            ec.entity.makeValue('moqui.a2a.A2AContext').setAll([
                contextId: contextId, userId: requireUser(ec), visitId: ec.user.visitId,
                lastTaskDate: ec.user.nowTimestamp, metadataJson: A2ATypes.toJson(metadata)]).create()
        }
    }

    private static EntityValue createTask(ExecutionContext ec, EntityValue a2aContext, String profile,
            Map<String, Object> metadata) {
        EntityValue task = disabled(ec) {
            ec.entity.makeValue('moqui.a2a.A2ATask').setAll([
                taskId: UUID.randomUUID().toString(), contextId: a2aContext.contextId,
                userId: requireUser(ec), visitId: ec.user.visitId, workerProfileName: profile,
                metadataJson: A2ATypes.toJson(metadata), cancelRequested: 'N', inFlight: 'N']).create()
        }
        disabled(ec) {
            a2aContext.setAll([lastTaskDate: ec.user.nowTimestamp, lastTaskId: task.taskId]).update()
        }
        setStatus(ec, task, A2ATypes.stateId(ec, 'TASK_STATE_SUBMITTED'), null, [:], true)
        task
    }

    private static EntityValue persistMessage(ExecutionContext ec, EntityValue task, Map<String, Object> message) {
        EntityValue messageValue = disabled(ec) {
            int sequenceNum = nextSequenceNum(ec, 'moqui.a2a.A2AMessage', [taskId: task.taskId])
            EntityValue value = ec.entity.makeValue('moqui.a2a.A2AMessage').setAll([
                messageId: message.messageId, userId: requireUser(ec), taskId: task.taskId,
                contextId: task.contextId, role: message.role,
                referenceTaskIdsJson: A2ATypes.toJson(message.referenceTaskIds),
                metadataJson: A2ATypes.toJson(message.metadata), extensionsJson: A2ATypes.toJson(message.extensions),
                sentDate: ec.user.nowTimestamp, sequenceNum: sequenceNum]).setSequencedIdPrimary()
            value.create()
            persistMessageParts(ec, value.a2aMessageId as String, message.parts as List<Map<String, Object>>)
            EntityValue a2aContext = ec.entity.find('moqui.a2a.A2AContext').condition('contextId', task.contextId)
                .forUpdate(true).one()
            if (a2aContext != null) a2aContext.set('lastA2aMessageId', value.a2aMessageId).update()
            value
        }
        messageValue
    }

    static void setStatus(ExecutionContext ec, EntityValue task, String statusId,
            Map<String, Object> statusMessage, Map<String, Object> extraFields, boolean initial = false) {
        EntityValue currentStatus = currentStatusValue(ec, task)
        if (!initial) A2ATypes.requireTransition(ec, currentStatus?.statusId as String, statusId)
        EntityValue statusValue
        disabled(ec) {
            EntityValue messageValue = ensureStatusMessage(ec, task, statusMessage)
            int sequenceNum = nextSequenceNum(ec, 'moqui.a2a.A2ATaskStatus', [taskId: task.taskId])
            statusValue = ec.entity.makeValue('moqui.a2a.A2ATaskStatus').setAll([
                taskId: task.taskId, contextId: task.contextId, statusId: statusId,
                a2aMessageId: messageValue?.a2aMessageId, statusTimestamp: ec.user.nowTimestamp,
                metadataJson: A2ATypes.toJson(statusMessage?.metadata), sequenceNum: sequenceNum])
                .setSequencedIdPrimary()
            statusValue.create()
            Map<String, Object> fields = [taskStatusId: statusValue.taskStatusId]
            if (extraFields != null) fields.putAll(extraFields)
            task.setAll(fields).update()
        }
        appendTaskEvent(ec, task, A2ATypes.EVENT_STATUS, statusValue, null, statusValue?.a2aMessageId as String,
            [statusUpdate: [taskId: task.taskId, contextId: task.contextId, status: statusMap(ec, statusValue)]]
                as Map<String, Object>, null, null)
    }

    /** Persists one StreamResponse ({statusUpdate} or {artifactUpdate}) for replay and SubscribeToTask. */
    private static void appendTaskEvent(ExecutionContext ec, EntityValue task, String eventTypeEnumId,
            EntityValue statusValue, String artifactId, String a2aMessageId, Map<String, Object> streamResponse,
            Boolean append, Boolean lastChunk) {
        disabled(ec) {
            int sequenceNum = nextSequenceNum(ec, 'moqui.a2a.A2ATaskEvent', [taskId: task.taskId])
            EntityValue eventValue = ec.entity.makeValue('moqui.a2a.A2ATaskEvent').setAll([
                taskId: task.taskId, contextId: task.contextId, eventTypeEnumId: eventTypeEnumId,
                taskStatusId: statusValue?.taskStatusId, artifactId: artifactId, a2aMessageId: a2aMessageId,
                append: append == null ? null : (append ? 'Y' : 'N'),
                lastChunk: lastChunk == null ? null : (lastChunk ? 'Y' : 'N'),
                payloadJson: A2ATypes.toJson(streamResponse), eventDate: ec.user.nowTimestamp, sequenceNum: sequenceNum])
                .setSequencedIdPrimary()
            eventValue.create()
        }
    }

    private static EntityValue findActiveTask(ExecutionContext ec, String contextId) {
        EntityList tasks = disabled(ec) {
            ec.entity.find('moqui.a2a.A2ATask').condition('contextId', contextId).list()
        }
        for (EntityValue task in tasks) if (!terminal(ec, task)) return task
        null
    }

    private static EntityValue findMessage(ExecutionContext ec, String userId, String messageId) {
        disabled(ec) {
            ec.entity.find('moqui.a2a.A2AMessage')
                .condition([userId: userId, messageId: messageId]).useCache(false).one()
        }
    }

    private static Map<String, Object> replayResult(ExecutionContext ec, EntityValue duplicate, Map<String, Object> request) {
        Map<String, Object> savedResult = A2ATypes.parseJson(duplicate.resultJson as String) as Map<String, Object>
        [
            replayed: true,
            pending: savedResult == null,
            result: savedResult,
            userId: duplicate.userId,
            messageId: duplicate.messageId,
            taskId: duplicate.taskId,
            task: taskMap(ec, ownedTask(ec, duplicate.taskId as String),
                A2ATypes.historyLength((request.configuration as Map)?.historyLength), true)
        ] as Map<String, Object>
    }

    /** Poll for the original SendMessage result. Does not hold a transaction or connection between reads. */
    static Map<String, Object> awaitReplay(ExecutionContext ec, Map<String, Object> accepted, Map<String, Object> request) {
        String userId = accepted.userId as String
        String messageId = accepted.messageId as String
        String taskId = accepted.taskId as String
        int historyLength = A2ATypes.historyLength((request.configuration as Map)?.historyLength)
        long deadline = System.currentTimeMillis() + A2ATypes.replayWaitMs()
        while (true) {
            Map<String, Object> snapshot = isolated(ec) {
                replaySnapshot(ec, userId, messageId, taskId, historyLength)
            }
            if (snapshot.result instanceof Map) return (Map<String, Object>) snapshot.result
            if (snapshot.done == true || System.currentTimeMillis() >= deadline)
                return [task: snapshot.task] as Map<String, Object>
            Thread.sleep(50L)
        }
    }

    private static Map<String, Object> replaySnapshot(ExecutionContext ec, String userId, String messageId,
            String taskId, int historyLength) {
        EntityValue duplicate = findMessage(ec, userId, messageId)
        Object parsed = A2ATypes.parseJson(duplicate?.resultJson as String)
        if (parsed instanceof Map) {
            Map<String, Object> saved = (Map<String, Object>) parsed
            return [result: saved.task != null ? saved : [task: saved] as Map<String, Object>] as Map<String, Object>
        }
        EntityValue task = ownedTask(ec, taskId)
        [done: terminal(ec, task) && task.inFlight != 'Y',
         task: taskMap(ec, task, historyLength, true)] as Map<String, Object>
    }

    private static EntityValue ownedTask(ExecutionContext ec, String taskId, boolean forUpdate = false) {
        if (A2ATypes.text(taskId) == null) throw new IllegalArgumentException('task id is required')
        EntityValue task = disabled(ec) {
            ec.entity.find('moqui.a2a.A2ATask').condition('taskId', taskId)
                .useCache(false).forUpdate(forUpdate).one()
        }
        // unknown and other-user tasks look the same to the caller
        if (task == null || task.userId != requireUser(ec)) throw new A2AException(A2AException.TASK_NOT_FOUND, 'task not found')
        task
    }

    private static EntityValue ownedContext(ExecutionContext ec, String contextId, boolean forUpdate = false) {
        EntityValue a2aContext = disabled(ec) {
            ec.entity.find('moqui.a2a.A2AContext').condition('contextId', contextId)
                    .useCache(false).forUpdate(forUpdate).one()
        }
        if (a2aContext == null || a2aContext.userId != requireUser(ec))
            throw new IllegalArgumentException('context not found')
        a2aContext
    }

    private static EntityValue currentStatusValue(ExecutionContext ec, EntityValue task) {
        if (task?.taskStatusId == null) return null
        disabled(ec) {
            ec.entity.find('moqui.a2a.A2ATaskStatus')
                .condition('taskStatusId', task.taskStatusId).useCache(false).one()
        }
    }

    private static EntityValue ensureStatusMessage(ExecutionContext ec, EntityValue task, Map<String, Object> statusMessage) {
        if (statusMessage == null) return null
        Map<String, Object> checked = A2ATypes.requireMessage(statusMessage, false)
        checked.contextId = task.contextId
        checked.taskId = task.taskId
        EntityValue existing = findMessage(ec, requireUser(ec), checked.messageId as String)
        existing ?: persistMessage(ec, task, checked)
    }

    /**
     * Next per-task sequence number. Every caller holds the A2ATask row for update in the same transaction
     * (ownedTask(..., true) or a task just created), so concurrent turns on one task serialize here; the unique
     * (taskId, sequenceNum) indexes are the backstop.
     */
    private static int nextSequenceNum(ExecutionContext ec, String entityName, Map<String, Object> conditionMap) {
        // list(), not one(): one() ignores orderBy and limit and would return an arbitrary row
        EntityFind entityFind = ec.entity.find(entityName).selectField('sequenceNum').orderBy('-sequenceNum').limit(1)
            .useCache(false)
        if (conditionMap != null) entityFind.condition(conditionMap)
        EntityList latest = entityFind.list()
        latest && latest.first().sequenceNum != null ? (latest.first().sequenceNum as Integer) + 1 : 1
    }

    private static String sequenceId(int sequenceNum) {
        sequenceNum < 100 ? String.format('%02d', sequenceNum) : Integer.toString(sequenceNum)
    }

    /** Parts must already be through A2ATypes.requireParts(); no public path persists Parts without it. */
    private static void persistMessageParts(ExecutionContext ec, String a2aMessageId, List<Map<String, Object>> parts) {
        for (int i = 0; i < parts.size(); i++) {
            int sequenceNum = i + 1
            ec.entity.makeValue('moqui.a2a.A2AMessagePart').setAll(partFields(parts.get(i)))
                .setAll([a2aMessageId: a2aMessageId, messagePartSeqId: sequenceId(sequenceNum),
                    sequenceNum: sequenceNum]).create()
        }
    }

    /** Parts must already be through A2ATypes.requireParts(); no public path persists Parts without it. */
    private static void persistArtifactParts(ExecutionContext ec, String taskId, String artifactId,
            List<Map<String, Object>> parts, boolean append) {
        int startSequenceNum = append ?
            nextSequenceNum(ec, 'moqui.a2a.A2AArtifactPart', [taskId: taskId, artifactId: artifactId]) : 1
        for (int i = 0; i < parts.size(); i++) {
            int sequenceNum = startSequenceNum + i
            ec.entity.makeValue('moqui.a2a.A2AArtifactPart').setAll(partFields(parts.get(i)))
                .setAll([taskId: taskId, artifactId: artifactId, artifactPartSeqId: sequenceId(sequenceNum),
                    sequenceNum: sequenceNum]).create()
        }
    }

    /** Columns for one validated Part (exactly one of text, raw, url, data). */
    private static Map<String, Object> partFields(Map<String, Object> part) {
        [partTypeEnumId: A2ATypes.partTypeEnumId(part), textContent: part.text,
         dataJson: part.data != null ? A2ATypes.jsonValue(part.data) : null, rawContent: part.raw, url: part.url,
         filename: part.filename, mediaType: part.mediaType, metadataJson: A2ATypes.toJson(part.metadata)] as Map<String, Object>
    }

    // ===== Wire mapping: entity rows to A2A 1.0 JSON objects =====

    private static Map<String, Object> statusMap(ExecutionContext ec, EntityValue statusValue) {
        Map<String, Object> status = [state: A2ATypes.wireState(ec, statusValue?.statusId as String)]
        if (statusValue?.a2aMessageId != null) {
            EntityValue message = disabled(ec) {
                ec.entity.find('moqui.a2a.A2AMessage')
                    .condition('a2aMessageId', statusValue.a2aMessageId).one()
            }
            if (message != null) status.message = messageMap(ec, message)
        }
        Timestamp timestamp = statusValue?.statusTimestamp as Timestamp
        if (timestamp != null) status.timestamp = timestamp.toInstant().toString()
        status
    }

    private static Map<String, Object> messageMap(ExecutionContext ec, EntityValue value) {
        Map<String, Object> message = [
            messageId: value.messageId,
            contextId: value.contextId,
            taskId: value.taskId,
            role: value.role,
            parts: messageParts(ec, value.a2aMessageId as String)
        ]
        Object refs = A2ATypes.parseJson(value.referenceTaskIdsJson as String)
        Object metadata = A2ATypes.parseJson(value.metadataJson as String)
        Object extensions = A2ATypes.parseJson(value.extensionsJson as String)
        if (refs != null) message.referenceTaskIds = refs
        if (metadata != null) message.metadata = metadata
        if (extensions != null) message.extensions = extensions
        message
    }

    private static List<Map<String, Object>> messageParts(ExecutionContext ec, String a2aMessageId) {
        EntityList rows = disabled(ec) {
            ec.entity.find('moqui.a2a.A2AMessagePart').condition('a2aMessageId', a2aMessageId)
                .orderBy('sequenceNum').list()
        }
        rows.collect { EntityValue row -> partMap(row) }
    }

    private static Map<String, Object> artifactMap(ExecutionContext ec, EntityValue value) {
        Map<String, Object> artifact = [
            artifactId: value.artifactId,
            name: value.name,
            description: value.description,
            parts: artifactParts(ec, value.taskId as String, value.artifactId as String)
        ]
        Object extensions = A2ATypes.parseJson(value.extensionsJson as String)
        Object metadata = A2ATypes.parseJson(value.metadataJson as String)
        if (extensions != null) artifact.extensions = extensions
        if (metadata != null) artifact.metadata = metadata
        A2ATypes.compact(artifact)
    }

    private static List<Map<String, Object>> artifactParts(ExecutionContext ec, String taskId, String artifactId) {
        EntityList rows = disabled(ec) {
            ec.entity.find('moqui.a2a.A2AArtifactPart').condition([taskId: taskId, artifactId: artifactId])
                .orderBy('sequenceNum').list()
        }
        rows.collect { EntityValue row -> partMap(row) }
    }

    private static Map<String, Object> partMap(EntityValue row) {
        Map<String, Object> part = new LinkedHashMap<>()
        switch (row.partTypeEnumId) {
            case A2ATypes.PART_TEXT: part.text = row.textContent ?: ''; break
            case A2ATypes.PART_RAW: part.raw = row.rawContent; break
            case A2ATypes.PART_URL: part.url = row.url; break
            case A2ATypes.PART_DATA: part.data = A2ATypes.parseJson(row.dataJson as String); break
        }
        if (row.filename) part.filename = row.filename
        if (row.mediaType) part.mediaType = row.mediaType
        Object metadata = A2ATypes.parseJson(row.metadataJson as String)
        if (metadata != null) part.metadata = metadata
        part
    }

    /** Push credentials are stored encrypted (encrypt="true") and are write-only: never returned on the API. */
    private static Map<String, Object> pushConfigMap(EntityValue value) {
        Map<String, Object> config = [id: value.configId, taskId: value.taskId, url: value.url] as Map<String, Object>
        Map<String, Object> authentication = A2ATypes.parseJson(value.authenticationJson as String) as Map<String, Object>
        if (authentication?.scheme) config.authentication = [scheme: authentication.scheme]
        config
    }

    private static String encodeCursor(EntityValue row) {
        String json = A2ATypes.toJson([statusDate: (row.statusTimestamp as Timestamp).toInstant().toString(), taskId: row.taskId])
        Base64.urlEncoder.withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8))
    }

    private static Map<String, Object> decodeCursor(String token) {
        if (token == null || token.isEmpty()) return [:]
        try {
            Object parsed = A2ATypes.parseJson(new String(Base64.urlDecoder.decode(token), StandardCharsets.UTF_8))
            if (!(parsed instanceof Map) || A2ATypes.text(((Map) parsed).statusDate) == null || A2ATypes.text(((Map) parsed).taskId) == null)
                throw new IllegalArgumentException('invalid cursor fields')
            Instant.parse(((Map) parsed).statusDate as String)
            (Map<String, Object>) parsed
        } catch (Throwable ignored) {
            throw new IllegalArgumentException('pageToken is invalid')
        }
    }

    private static String encodeOffset(int offset) {
        Base64.urlEncoder.withoutPadding().encodeToString(Integer.toString(offset).getBytes(StandardCharsets.UTF_8))
    }

    private static int decodeOffset(String token) {
        if (token == null || token.isEmpty()) return 0
        try {
            return new String(Base64.urlDecoder.decode(token), StandardCharsets.UTF_8) as int
        } catch (Throwable ignored) {
            throw new IllegalArgumentException('pageToken is invalid')
        }
    }

    private static String requireUser(ExecutionContext ec) {
        String userId = ec?.user?.userId
        if (userId == null) throw new SecurityException('authenticated user is required')
        userId
    }

    static boolean terminal(ExecutionContext ec, EntityValue task) {
        A2ATypes.terminal(ec, currentStatusValue(ec, task)?.statusId as String)
    }

    static String currentState(ExecutionContext ec, EntityValue task) {
        A2ATypes.wireState(ec, currentStatusValue(ec, task)?.statusId as String)
    }

    static Map<String, Object> cleanData(ExecutionContext ec, Map<String, Object> request) {
        isolated(ec) {
            int daysToKeep = request.daysToKeep != null ? request.daysToKeep as int : 90
            Calendar calendar = ec.user.getCalendarSafe()
            calendar.add(Calendar.DAY_OF_YEAR, -daysToKeep)
            Timestamp before = new Timestamp(calendar.timeInMillis)
            List<String> taskIds = disabled(ec) {
                ec.entity.find('moqui.a2a.A2ATaskAndStatus').condition('statusTimestamp', EntityCondition.LESS_THAN, before)
                    .condition('statusId', EntityCondition.IN, A2ATypes.terminalStatusIds(ec) as List)
                    .selectField('taskId').useCache(false).list().collect { EntityValue task -> task.taskId as String }
            } as List<String>
            long tasksRemoved = 0L
            long contextsRemoved = 0L
            if (!taskIds.isEmpty()) {
                disabled(ec) {
                    ec.entity.find('moqui.a2a.A2APushConfig').condition('taskId', EntityCondition.IN, taskIds).deleteAll()
                    ec.entity.find('moqui.a2a.A2ATaskEvent').condition('taskId', EntityCondition.IN, taskIds).deleteAll()
                    ec.entity.find('moqui.a2a.A2AArtifactPart').condition('taskId', EntityCondition.IN, taskIds).deleteAll()
                    ec.entity.find('moqui.a2a.A2AArtifact').condition('taskId', EntityCondition.IN, taskIds).deleteAll()
                    ec.entity.find('moqui.a2a.A2ATaskStatus').condition('taskId', EntityCondition.IN, taskIds).deleteAll()
                    EntityList messages = ec.entity.find('moqui.a2a.A2AMessage').condition('taskId', EntityCondition.IN, taskIds).list()
                    List<String> a2aMessageIds = messages.collect { EntityValue message -> message.a2aMessageId as String }
                    if (!a2aMessageIds.isEmpty())
                        ec.entity.find('moqui.a2a.A2AMessagePart').condition('a2aMessageId', EntityCondition.IN, a2aMessageIds).deleteAll()
                    ec.entity.find('moqui.a2a.A2AMessage').condition('taskId', EntityCondition.IN, taskIds).deleteAll()
                    tasksRemoved = ec.entity.find('moqui.a2a.A2ATask').condition('taskId', EntityCondition.IN, taskIds).deleteAll()
                }
            }
            EntityList contexts = disabled(ec) {
                ec.entity.find('moqui.a2a.A2AContext').condition('lastTaskDate', EntityCondition.LESS_THAN, before).list()
            }
            List<String> unusedContextIds = new ArrayList<>()
            for (EntityValue a2aContext in contexts) {
                long taskCount = disabled(ec) {
                    ec.entity.find('moqui.a2a.A2ATask').condition('contextId', a2aContext.contextId).count()
                }
                long conversationCount = disabled(ec) {
                    ec.entity.find('moqui.llm.LlmConversation').condition('contextId', a2aContext.contextId).count()
                }
                if (taskCount == 0L && conversationCount == 0L) unusedContextIds.add(a2aContext.contextId as String)
            }
            if (!unusedContextIds.isEmpty()) {
                contextsRemoved = disabled(ec) {
                    ec.entity.find('moqui.a2a.A2AContext')
                        .condition('contextId', EntityCondition.IN, unusedContextIds).deleteAll()
                }
            }
            [tasksRemoved: tasksRemoved, contextsRemoved: contextsRemoved] as Map<String, Object>
        }
    }

    // ===== Transaction and authorization boundaries =====

    /** Same-thread require-new transaction via persistIsolated; not an LLM call. */
    static <T> T isolated(ExecutionContext ec, Closure<T> work) {
        Object[] result = new Object[1]
        LlmConversationImpl.persistIsolated(ec, { result[0] = work.call() } as Runnable)
        (T) result[0]
    }

    static <T> T disabled(ExecutionContext ec, Closure<T> work) {
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        try {
            return work.call()
        } finally {
            if (!alreadyDisabled) ec.artifactExecution.enableAuthz()
        }
    }
}
