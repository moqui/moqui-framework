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

import groovy.transform.CompileStatic
import org.elasticsearch.client.Client
import org.elasticsearch.node.NodeBuilder
import org.moqui.context.ExecutionContextFactory
import org.moqui.context.ToolFactory
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** ElasticSearch Client is used for indexing and searching documents */
@CompileStatic
class ElasticSearchToolFactory implements ToolFactory<Client> {
    protected final static Logger logger = LoggerFactory.getLogger(ElasticSearchToolFactory.class)
    final static String TOOL_NAME = "ElasticSearch"

    protected ExecutionContextFactory ecf = null

    /** ElasticSearch Node */
    protected org.elasticsearch.node.Node elasticSearchNode
    /** ElasticSearch Client */
    protected Client elasticSearchClient

    /** Default empty constructor */
    ElasticSearchToolFactory() { }

    @Override
    String getName() { return TOOL_NAME }
    @Override
    void init(ExecutionContextFactory ecf) {
        this.ecf = ecf

        // set the ElasticSearch home directory
        if (!System.getProperty("es.path.home")) System.setProperty("es.path.home", ecf.runtimePath + "/elasticsearch")
        logger.info("Starting ElasticSearch, home at ${System.getProperty("es.path.home")}")
        elasticSearchNode = NodeBuilder.nodeBuilder().node()
        elasticSearchClient = elasticSearchNode.client()
    }
    @Override
    void preFacadeInit(ExecutionContextFactory ecf) { }

    @Override
    Client getInstance() {
        if (elasticSearchClient == null) throw new IllegalStateException("ElasticSearchToolFactory not initialized")
        return elasticSearchClient
    }

    @Override
    void destroy() {
        if (elasticSearchNode != null) try {
            elasticSearchNode.close()
            while (!elasticSearchNode.isClosed()) {
                logger.info("ElasticSearch still closing")
                this.wait(1000)
            }
            logger.info("ElasticSearch closed")
        } catch (Throwable t) { logger.error("Error in ElasticSearch node close", t) }
    }

    ExecutionContextFactory getEcf() { return ecf }
}
