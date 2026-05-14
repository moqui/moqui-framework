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
package org.moqui.impl.context

import groovy.transform.CompileStatic

import org.moqui.context.AiFacade
import org.moqui.util.MNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
class AiFacadeImpl implements AiFacade {
    protected final static Logger logger = LoggerFactory.getLogger(AiFacadeImpl.class)

    public final ExecutionContextFactoryImpl ecfi
    private final Map<String, AiFacade.AiClient> clientByName = new LinkedHashMap<>()
    private String defaultConfigName = (String) null

    AiFacadeImpl(ExecutionContextFactoryImpl ecfi) {
        this.ecfi = ecfi
        init()
    }

    void init() {
        MNode aiFacadeNode = ecfi.getConfXmlRoot().first("ai-facade")
        if (aiFacadeNode == null) {
            logger.info("No ai-facade configuration found")
            return
        }
        defaultConfigName = aiFacadeNode.attribute("default-config")

        for (MNode modelConfigNode in aiFacadeNode.children("model-config")) {
            modelConfigNode.setSystemExpandAttributes(true)

            String name       = modelConfigNode.attribute("name")
            String provider   = modelConfigNode.attribute("provider")
            String model      = modelConfigNode.attribute("model")
            String baseUrl    = modelConfigNode.attribute("base-url")
            String apiKey     = modelConfigNode.attribute("api-key") ?: ""
            String timeoutStr = modelConfigNode.attribute("timeout")
            String poolMaxStr = modelConfigNode.attribute("pool-max")
            // pool-max reserved for future OkHttp client configuration

            if (!provider) {
                logger.warn("AiFacade model-config '${name}' has no provider, skipping")
                continue
            }

            if (!model)   logger.warn("AiFacade model-config '${name}' has no model configured")
            if (!apiKey)  logger.warn("AiFacade model-config '${name}' has no api-key configured, set ai_api_key as a property or environment variable")

            logger.info("AiFacade model-config '${name}' provider=${provider} model=${model} base-url=${baseUrl} timeout=${timeoutStr}${poolMaxStr ? ' pool-max=' + poolMaxStr : ''}")
        }
    }

    void destroy() {
        clientByName.clear()
    }

    @Override
    AiFacade.AiClient getDefault() {
        String name = defaultConfigName ?: "default"
        AiFacade.AiClient client = clientByName.get(name)
        if (client == null) throw new IllegalStateException(
                "AiFacade model-config '${name}' has no api-key configured. " +
                "Set ai_api_key in MoquiConf.xml or as an environment variable.")
        return client
    }

    @Override
    AiFacade.AiClient getConfig(String name) {
        AiFacade.AiClient client = clientByName.get(name)
        if (client == null) throw new IllegalStateException(
                "AiFacade model-config '${name}' has no api-key configured. " +
                "Set ai_api_key in MoquiConf.xml or as an environment variable.")
        return client
    }
}
