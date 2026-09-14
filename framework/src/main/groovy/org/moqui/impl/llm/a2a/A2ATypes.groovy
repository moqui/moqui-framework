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

import org.moqui.BaseException
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityValue
import org.moqui.impl.context.ContextJavaUtil
import org.moqui.util.SystemBinding

import java.net.URI
import java.nio.charset.StandardCharsets

/**
 * A2A 1.0 protocol semantics: input validation, Part shapes, task states and allowed transitions, plus the JSON
 * helpers used on the wire. No persistence, no LLM, no transport; the only database access is the Enumeration
 * lookup that maps wire task states to their enum ids.
 */
final class A2ATypes {
    static final Set<String> OUTPUT_MODES = ['text/plain', 'application/json'] as Set<String>

    private static final String TASK_STATUS_TYPE = 'A2ATaskStatus'

    private static final String PART_TEXT = 'A2APrtText'

    private static final String PART_DATA = 'A2APrtData'

    private static final String PART_RAW = 'A2APrtRaw'

    private static final String PART_URL = 'A2APrtUrl'

    private static final List<String> PART_VARIANTS = ['text', 'raw', 'url', 'data'].asImmutable()

    private static final String EVENT_STATUS = 'A2AEvtStatus'

    private static final String EVENT_ARTIFACT = 'A2AEvtArtifact'

    private static final Set<String> TERMINAL_WIRE_STATES =

            ['TASK_STATE_COMPLETED', 'TASK_STATE_FAILED', 'TASK_STATE_CANCELED', 'TASK_STATE_REJECTED'] as Set<String>

    private static final Set<String> INTERRUPTED_WIRE_STATES =

            ['TASK_STATE_INPUT_REQUIRED', 'TASK_STATE_AUTH_REQUIRED'] as Set<String>

    private static final Map<String, Set<String>> ALLOWED_WIRE_TRANSITIONS = [
            TASK_STATE_SUBMITTED : ['TASK_STATE_WORKING', 'TASK_STATE_CANCELED', 'TASK_STATE_REJECTED',
                'TASK_STATE_FAILED'] as Set<String>,
            TASK_STATE_WORKING : ['TASK_STATE_COMPLETED', 'TASK_STATE_FAILED', 'TASK_STATE_CANCELED',
                'TASK_STATE_INPUT_REQUIRED', 'TASK_STATE_AUTH_REQUIRED'] as Set<String>,
            TASK_STATE_INPUT_REQUIRED: ['TASK_STATE_WORKING', 'TASK_STATE_CANCELED', 'TASK_STATE_FAILED'] as Set<String>,
            TASK_STATE_AUTH_REQUIRED : ['TASK_STATE_WORKING', 'TASK_STATE_CANCELED', 'TASK_STATE_FAILED'] as Set<String>]

    /** Moqui LLM profile used for A2A work when the request does not name one (a2a_default_profile, default assist). */
    static String defaultProfile() {
        SystemBinding.getPropOrEnv('a2a_default_profile')?.trim() ?: 'assist'
    }

    static int maxParts() { positiveInt('a2a_max_parts', 32) }

    static int maxPartBytes() { positiveInt('a2a_max_part_bytes', 65536) }

    static long replayWaitMs() { positiveLong('a2a_replay_wait_ms', 600000L) }

    private static String partTypeEnumId(Map<String, Object> part) {
        if (part.containsKey('text')) return PART_TEXT
        if (part.containsKey('raw')) return PART_RAW
        if (part.containsKey('url')) return PART_URL
        if (part.containsKey('data')) return PART_DATA
        throw new IllegalArgumentException('unknown A2A Part type')
    }

    // ===== Validation: the only place A2A input invariants are enforced =====

    static Map<String, Object> requireMessage(Object value, boolean userOnly = false) {
        if (!(value instanceof Map)) throw new IllegalArgumentException('message must be an object')
        Map<String, Object> message = new LinkedHashMap<>((Map<String, Object>) value)
        if (text(message.messageId) == null) throw new IllegalArgumentException('message.messageId is required')
        String role = text(message.role)
        if (!(role in ['ROLE_USER', 'ROLE_AGENT'])) throw new IllegalArgumentException('message.role must be ROLE_USER or ROLE_AGENT')
        if (userOnly && role != 'ROLE_USER') throw new IllegalArgumentException('SendMessage requires ROLE_USER')
        message.parts = requireParts(message.parts)
        if (message.contextId != null && text(message.contextId) == null)
            throw new IllegalArgumentException('message.contextId must not be blank')
        if (message.taskId != null && text(message.taskId) == null)
            throw new IllegalArgumentException('message.taskId must not be blank')
        if (message.referenceTaskIds != null && !(message.referenceTaskIds instanceof List))
            throw new IllegalArgumentException('message.referenceTaskIds must be an array')
        requireStringList(message.referenceTaskIds, 'message.referenceTaskIds')
        requireStringList(message.extensions, 'message.extensions')
        requireMap(message.metadata, 'message.metadata')
        message
    }

    /** A2A 1.0 Part: exactly one of text, raw (base64 bytes), url, or data (any JSON value), plus filename/mediaType/metadata. */
    static List<Map<String, Object>> requireParts(Object value) {
        if (!(value instanceof List) || ((List) value).isEmpty())
            throw new IllegalArgumentException('message.parts must contain at least one Part')
        if (((List) value).size() > maxParts())
            throw new IllegalArgumentException("message.parts exceeds a2a_max_parts (${maxParts()})")
        List<Map<String, Object>> parts = new ArrayList<>()
        for (Object partValue in (List) value) {
            if (!(partValue instanceof Map)) throw new IllegalArgumentException('each Part must be an object')
            Map<String, Object> part = new LinkedHashMap<>((Map<String, Object>) partValue)
            List<String> variants = PART_VARIANTS.findAll { String key -> part.containsKey(key) }
            if (variants.size() != 1)
                throw new IllegalArgumentException('each Part must contain exactly one of text, raw, url, or data')
            String variant = variants.first()
            if (variant == 'text') {
                if (!(part.text instanceof String)) throw new IllegalArgumentException('Part.text must be a string')
                int textBytes = ((String) part.text).getBytes(StandardCharsets.UTF_8).length
                if (textBytes > maxPartBytes())
                    throw new IllegalArgumentException("Part.text exceeds a2a_max_part_bytes (${maxPartBytes()})")
            }
            if (variant == 'raw') requireBase64(part.raw)
            if (variant == 'url' && (!(part.url instanceof String) || text(part.url) == null))
                throw new IllegalArgumentException('Part.url must be a non-blank string')
            if (part.filename != null && !(part.filename instanceof String))
                throw new IllegalArgumentException('Part.filename must be a string')
            if (part.mediaType != null && !(part.mediaType instanceof String))
                throw new IllegalArgumentException('Part.mediaType must be a string')
            requireMap(part.metadata, 'Part.metadata')
            parts.add(part)
        }
        parts
    }

    private static int requireBase64(Object value) {
        if (!(value instanceof String) || ((String) value).isEmpty())
            throw new IllegalArgumentException('Part.raw must be base64 encoded bytes')
        byte[] decoded
        try {
            decoded = Base64.decoder.decode((String) value)
        } catch (IllegalArgumentException ignored) {
            try {
                decoded = Base64.urlDecoder.decode((String) value)
            } catch (IllegalArgumentException ignoredAgain) {
                throw new IllegalArgumentException('Part.raw must be base64 encoded bytes')
            }
        }
        return checkDecodedLength(decoded.length)
    }

    private static int checkDecodedLength(int bytes) {
        if (bytes > maxPartBytes())
            throw new IllegalArgumentException("Part.raw exceeds a2a_max_part_bytes (${maxPartBytes()})")
        bytes
    }

    static Map<String, Object> requireConfiguration(Object value) {
        if (value == null) return [:]
        if (!(value instanceof Map)) throw new IllegalArgumentException('configuration must be an object')
        Map<String, Object> configuration = new LinkedHashMap<>((Map<String, Object>) value)
        if (configuration.acceptedOutputModes != null) {
            requireStringList(configuration.acceptedOutputModes, 'configuration.acceptedOutputModes')
            Set<String> requested = (configuration.acceptedOutputModes as List).collect { Object mode -> text(mode) } as Set<String>
            if (requested != null && !requested.isEmpty() && requested.intersect(OUTPUT_MODES).isEmpty())
                throw new A2AException(A2AException.CONTENT_TYPE_NOT_SUPPORTED,
                    "none of the acceptedOutputModes are supported: ${requested}")
        }
        if (configuration.historyLength != null) historyLength(configuration.historyLength)
        if (configuration.returnImmediately == true)
            throw new A2AException(A2AException.UNSUPPORTED_OPERATION,
                'returnImmediately is not supported: tasks run on the request thread')
        if (configuration.taskPushNotificationConfig != null)
            throw new A2AException(A2AException.PUSH_NOTIFICATION_NOT_SUPPORTED, 'push notifications are not supported')
        configuration
    }

    static String stateId(ExecutionContext ec, Object value) {
        String state = text(value)
        if (state == null) throw new IllegalArgumentException('task state is required')
        EntityValue byCode = disabled(ec) {
            ec.entity.find('moqui.basic.Enumeration')
                .condition([enumTypeId: TASK_STATUS_TYPE, enumCode: state]).useCache(true).one()
        }
        if (byCode != null) return byCode.enumId as String
        EntityValue byId = disabled(ec) {
            ec.entity.find('moqui.basic.Enumeration')
                .condition([enumTypeId: TASK_STATUS_TYPE, enumId: state]).useCache(true).one()
        }
        if (byId != null) return byId.enumId as String
        throw new IllegalArgumentException("unknown A2A task state ${state}")
    }

    static String wireState(ExecutionContext ec, String statusId) {
        if (statusId == null) return 'TASK_STATE_UNSPECIFIED'
        EntityValue value = disabled(ec) {
            ec.entity.find('moqui.basic.Enumeration')
                .condition([enumTypeId: TASK_STATUS_TYPE, enumId: statusId]).useCache(true).one()
        }
        text(value?.enumCode) ?: 'TASK_STATE_UNSPECIFIED'
    }

    static boolean terminalState(String wireState) { TERMINAL_WIRE_STATES.contains(wireState) }

    static boolean terminal(ExecutionContext ec, String statusId) {
        terminalState(wireState(ec, statusId))
    }

    static boolean interrupted(ExecutionContext ec, String statusId) {
        INTERRUPTED_WIRE_STATES.contains(wireState(ec, statusId))
    }

    static Set<String> terminalStatusIds(ExecutionContext ec) {
        disabled(ec) {
            ec.entity.find('moqui.basic.Enumeration').condition('enumTypeId', TASK_STATUS_TYPE)
                .condition('enumCode', EntityCondition.IN, TERMINAL_WIRE_STATES as List)
                .useCache(true).list().collect { EntityValue value -> value.enumId as String } as Set<String>
        }
    }

    static void requireTransition(ExecutionContext ec, String fromStatusId, String toStatusId) {
        if (fromStatusId == toStatusId) return
        String fromState = wireState(ec, fromStatusId)
        String toState = wireState(ec, toStatusId)
        if (!(ALLOWED_WIRE_TRANSITIONS[fromState] ?: Collections.emptySet()).contains(toState))
            throw new IllegalStateException("invalid A2A task transition ${fromState} -> ${toState}")
    }

    static int historyLength(Object value) {
        if (value == null) return 20
        int length = value as int
        if (length < 0) throw new IllegalArgumentException('historyLength must be zero or greater')
        Math.min(length, 50)
    }

    static int pageSize(Object value) {
        if (value == null) return 50
        int size = value as int
        if (size < 1) throw new IllegalArgumentException('pageSize must be at least 1')
        Math.min(size, 100)
    }

    /** Text handed to the LLM for a message. Raw bytes stay on the task; the model only sees a reference. */
    static String messageText(Map<String, Object> message) {
        List<String> chunks = new ArrayList<>()
        for (Map<String, Object> part in message.parts as List<Map<String, Object>>) {
            String media = part.mediaType ? " (${part.mediaType})" : ''
            if (part.containsKey('text')) chunks.add(part.text as String)
            else if (part.containsKey('data')) chunks.add(jsonValue(part.data))
            else if (part.containsKey('url')) chunks.add("[File ${part.filename ?: ''}${media} at ${part.url}]".toString())
            else if (part.containsKey('raw'))
                chunks.add("[Attached file ${part.filename ?: 'unnamed'}${media}, ${requireBase64(part.raw)} bytes, stored with the task]".toString())
        }
        chunks.join('\n')
    }

    static String toJson(Object value) {
        if (value == null) return null
        if (value instanceof String) return value as String
        jsonValue(value)
    }

    /** JSON for any value, including a bare string (A2A data Parts are google.protobuf.Value). */
    private static String jsonValue(Object value) {
        try {
            return ContextJavaUtil.jacksonMapper.writeValueAsString(value)
        } catch (Throwable t) {
            throw new BaseException('Error writing A2A JSON', t)
        }
    }

    static Object parseJson(String value) {
        if (value == null || value.isEmpty()) return null
        try {
            return ContextJavaUtil.jacksonMapper.readValue(value, Object.class)
        } catch (Throwable t) {
            throw new BaseException('Error parsing A2A JSON', t)
        }
    }

    private static int positiveInt(String name, int fallback) {
        String raw = SystemBinding.getPropOrEnv(name)?.trim()
        if (!raw) return fallback
        try {
            int value = Integer.parseInt(raw)
            return value > 0 ? value : fallback
        } catch (NumberFormatException ignored) {
            return fallback
        }
    }

    private static long positiveLong(String name, long fallback) {
        String raw = SystemBinding.getPropOrEnv(name)?.trim()
        if (!raw) return fallback
        try {
            long value = Long.parseLong(raw)
            return value > 0L ? value : fallback
        } catch (NumberFormatException ignored) {
            return fallback
        }
    }

    static String text(Object value) {
        String string = value?.toString()?.trim()
        string ?: null
    }

    private static Map<String, Object> compact(Map<String, Object> map) {
        map.findAll { Map.Entry<String, Object> entry -> entry.value != null } as Map<String, Object>
    }

    static void requireStringList(Object value, String path) {
        if (value == null) return
        if (!(value instanceof List) || ((List) value).any { Object item -> text(item) == null })
            throw new IllegalArgumentException("${path} must be an array of non-blank strings")
    }

    static void requireMap(Object value, String path) {
        if (value != null && !(value instanceof Map)) throw new IllegalArgumentException("${path} must be an object")
    }

    private static void requirePushUrl(String value) {
        URI uri
        try {
            uri = new URI(value)
        } catch (Throwable ignored) {
            throw new IllegalArgumentException('url must be an absolute HTTP(S) URI')
        }
        if (!(uri.scheme?.toLowerCase() in ['http', 'https']) || uri.host == null || uri.userInfo != null || uri.fragment != null)
            throw new IllegalArgumentException('url must be an absolute HTTP(S) URI without user-info or fragment')
    }

    private static <T> T disabled(ExecutionContext ec, Closure<T> work) {
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        try {
            return work.call()
        } finally {
            if (!alreadyDisabled) ec.artifactExecution.enableAuthz()
        }
    }
}
