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
package org.moqui.impl.tools

import com.hazelcast.core.HazelcastInstance
import com.hazelcast.core.ITopic
import com.hazelcast.core.Message
import com.hazelcast.core.MessageListener
import groovy.transform.CompileStatic
import org.moqui.BaseException
import org.moqui.context.ExecutionContextFactory
import org.moqui.context.ToolFactory
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.entity.EntityCache.EntityCacheInvalidate
import org.moqui.impl.entity.EntityFacadeImpl
import org.moqui.util.SimpleTopic
import org.slf4j.Logger
import org.slf4j.LoggerFactory


/** A factory for getting a Topic to publish to (actually Hazelcast ITopic) and adds a MessageListener for distributed entity cache invalidation. */
@CompileStatic
class HazelcastDciTopicToolFactory implements ToolFactory<SimpleTopic<EntityCacheInvalidate>> {
    protected final static Logger logger = LoggerFactory.getLogger(HazelcastDciTopicToolFactory.class)
    final static String TOOL_NAME = "HazelcastDciTopic"

    private ExecutionContextFactoryImpl ecfi = null

    /** Hazelcast Instance */
    private HazelcastInstance hazelcastInstance = null
    /** Entity Cache Invalidate Hazelcast Topic */
    private SimpleTopic<EntityCacheInvalidate> entityCacheInvalidateTopic = null

    /** Default empty constructor */
    HazelcastDciTopicToolFactory() { }

    @Override
    String getName() { return TOOL_NAME }
    @Override
    void init(ExecutionContextFactory ecf) { }
    @Override
    void preFacadeInit(ExecutionContextFactory ecf) {
        ecfi = (ExecutionContextFactoryImpl) ecf

        ToolFactory<HazelcastInstance> hzToolFactory = ecf.getToolFactory(HazelcastToolFactory.TOOL_NAME)
        if (hzToolFactory == null) {
            throw new BaseException("HazelcastToolFactory not in place, cannot use HazelcastDciTopicToolFactory")
        } else {
            logger.info("Getting Entity Cache Invalidate Hazelcast Topic and registering MessageListener")
            HazelcastInstance hazelcastInstance = hzToolFactory.getInstance()
            ITopic<EntityCacheInvalidate> iTopic = hazelcastInstance.getTopic("entity-cache-invalidate")
            EntityCacheListener eciListener = new EntityCacheListener(ecfi)
            iTopic.addMessageListener(eciListener)
            entityCacheInvalidateTopic = new TopicWrapper(iTopic)
        }
    }

    @Override
    SimpleTopic<EntityCacheInvalidate> getInstance() {
        if (entityCacheInvalidateTopic == null) throw new IllegalStateException("HazelcastDciTopicToolFactory not initialized")
        return entityCacheInvalidateTopic
    }

    @Override
    void destroy() {
        // do nothing, Hazelcast shutdown in HazelcastToolFactory
    }

    ExecutionContextFactoryImpl getEcfi() { return ecfi }

    static class TopicWrapper implements SimpleTopic<EntityCacheInvalidate> {
        ITopic<EntityCacheInvalidate> iTopic
        TopicWrapper(ITopic<EntityCacheInvalidate> iTopic) { this.iTopic = iTopic }
        @Override
        void publish(EntityCacheInvalidate message) { iTopic.publish(message) }
    }

    static class EntityCacheListener implements MessageListener<EntityCacheInvalidate> {
        ExecutionContextFactoryImpl ecfi
        EntityCacheListener(ExecutionContextFactoryImpl ecfi) {
            this.ecfi = ecfi
        }

        @Override
        void onMessage(Message<EntityCacheInvalidate> message) {
            EntityCacheInvalidate eci = message.getMessageObject()
            if (eci.tenantId == null) {
                logger.warn("Received EntityCacheInvalidate message with null tenantId, ignoring")
                return
            }
            // logger.info("====== EntityCacheListener message tenantId=${eci.tenantId} isCreate=${eci.isCreate}, evb: ${eci.evb}")
            ExecutionContextImpl.ThreadPoolRunnable runnable = new ExecutionContextImpl.ThreadPoolRunnable(ecfi, eci.tenantId, null, {
                EntityFacadeImpl efi = ecfi.getEntityFacade(eci.tenantId)
                efi.getEntityCache().clearCacheForValueActual(eci.evb, eci.isCreate)
            })
            ecfi.workerPool.execute(runnable)
        }
    }

}
