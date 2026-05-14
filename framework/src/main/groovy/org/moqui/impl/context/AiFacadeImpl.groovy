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

import groovy.json.JsonSlurper
import groovy.transform.CompileStatic

import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.request.ResponseFormat
import dev.langchain4j.model.chat.request.ResponseFormatType
import dev.langchain4j.model.chat.request.json.JsonArraySchema
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema
import dev.langchain4j.model.chat.request.json.JsonNumberSchema
import dev.langchain4j.model.chat.request.json.JsonObjectSchema
import dev.langchain4j.model.chat.request.json.JsonSchema
import dev.langchain4j.model.chat.request.json.JsonSchemaElement
import dev.langchain4j.model.chat.request.json.JsonStringSchema
import dev.langchain4j.model.openai.OpenAiChatModel

import org.moqui.context.AiFacade
import org.moqui.util.MNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.time.Duration

@CompileStatic
class AiFacadeImpl implements AiFacade {
    protected final static Logger logger = LoggerFactory.getLogger(AiFacadeImpl.class)

    public final ExecutionContextFactoryImpl ecfi
    private final Map<String, AiClientImpl> clientByName = new LinkedHashMap<>()
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

            def builder = OpenAiChatModel.builder()
                    .apiKey(apiKey ?: "no-key")
                    .modelName(model ?: "")
            if (baseUrl)    builder.baseUrl(baseUrl)
            if (timeoutStr) builder.timeout(Duration.ofSeconds(Long.parseLong(timeoutStr)))

            ChatModel chatModel = builder.build()
            clientByName.put(name, new AiClientImpl(provider, model ?: "", apiKey, chatModel))
            logger.info("Initialized AiFacade client '${name}' provider=${provider} model=${model}${poolMaxStr ? ' pool-max=' + poolMaxStr : ''}")
        }
    }

    void destroy() {
        clientByName.clear()
    }

    @Override
    AiFacade.AiClient getDefault() {
        String name = defaultConfigName ?: "default"
        AiClientImpl client = clientByName.get(name)
        if (client == null || !client.apiKey) throw new IllegalStateException(
                "AiFacade model-config '${name}' has no api-key configured. " +
                "Set ai_api_key in MoquiConf.xml or as an environment variable.")
        return client
    }

    @Override
    AiFacade.AiClient getConfig(String name) {
        AiClientImpl client = clientByName.get(name)
        if (client == null || !client.apiKey) throw new IllegalStateException(
                "AiFacade model-config '${name}' has no api-key configured. " +
                "Set ai_api_key in MoquiConf.xml or as an environment variable.")
        return client
    }

    @CompileStatic
    static class AiClientImpl implements AiFacade.AiClient {
        private static final Map<String, Closure<JsonSchemaElement>> TYPE_BUILDERS = ([
                "string":  { new JsonStringSchema()  } as Closure<JsonSchemaElement>,
                "number":  { new JsonNumberSchema()  } as Closure<JsonSchemaElement>,
                "integer": { new JsonIntegerSchema() } as Closure<JsonSchemaElement>,
                "boolean": { new JsonBooleanSchema() } as Closure<JsonSchemaElement>,
        ] as Map<String, Closure<JsonSchemaElement>>).asImmutable()

        final String provider
        final String model
        final String apiKey
        private final ChatModel chatModel

        AiClientImpl(String provider, String model, String apiKey, ChatModel chatModel) {
            this.provider  = provider
            this.model     = model
            this.apiKey    = apiKey
            this.chatModel = chatModel
        }

        @Override
        String generate(List<Map> messages) {
            String correlationId = UUID.randomUUID().toString()
            List<ChatMessage> chatMessages = buildChatMessages(messages)
            if (logger.isDebugEnabled())
                logger.debug("[${correlationId}] generate request: provider=${provider} model=${model} messages=${messages.size()}")
            ChatRequest request = ChatRequest.builder().messages(chatMessages).build()
            String responseText = chatModel.chat(request).aiMessage().text()
            if (logger.isDebugEnabled())
                logger.debug("[${correlationId}] generate response: ${responseText}")
            return responseText
        }

        @Override
        Map generateStructured(List<Map> messages, Map schema) {
            String correlationId = UUID.randomUUID().toString()
            List<ChatMessage> chatMessages = buildChatMessages(messages)
            JsonObjectSchema jsonObjectSchema = buildJsonObjectSchema(schema)
            ResponseFormat responseFormat = ResponseFormat.builder()
                    .type(ResponseFormatType.JSON)
                    .jsonSchema(JsonSchema.builder()
                            .name("response")
                            .rootElement(jsonObjectSchema)
                            .build())
                    .build()
            if (logger.isDebugEnabled())
                logger.debug("[${correlationId}] generateStructured request: provider=${provider} model=${model} messages=${messages.size()} schema=${schema.keySet()}")
            ChatRequest request = ChatRequest.builder().messages(chatMessages).responseFormat(responseFormat).build()
            String responseText = chatModel.chat(request).aiMessage().text()
            if (logger.isDebugEnabled())
                logger.debug("[${correlationId}] generateStructured response: ${responseText}")
            return (Map) new JsonSlurper().parseText(responseText)
        }

        private List<ChatMessage> buildChatMessages(List<Map> messages) {
            List<ChatMessage> chatMessages = new ArrayList<>(messages.size())
            for (Map msg in messages) {
                String role    = (String) msg.get("role")
                String content = (String) msg.get("content")
                if ("system".equals(role)) {
                    chatMessages.add(SystemMessage.from(content))
                } else if ("assistant".equals(role)) {
                    chatMessages.add(AiMessage.from(content))
                } else {
                    chatMessages.add(UserMessage.from(content))
                }
            }
            return chatMessages
        }

        private JsonObjectSchema buildJsonObjectSchema(Map schema) {
            JsonObjectSchema.Builder builder = JsonObjectSchema.builder()
            for (Object key in schema.keySet()) {
                String fieldName = (String) key
                Map    fieldDef  = (Map) schema.get(key)
                builder.addProperty(fieldName, buildSchemaElement(fieldDef))
            }
            return builder.build()
        }

        private JsonSchemaElement buildSchemaElement(Map fieldDef) {
            String type = ((String) fieldDef.get("type"))?.toLowerCase() ?: "string"
            if ("array".equals(type)) {
                Map items = (Map) fieldDef.get("items")
                JsonObjectSchema itemSchema = items
                        ? buildJsonObjectSchema(items)
                        : JsonObjectSchema.builder().build()
                return JsonArraySchema.builder().items(itemSchema).build()
            }
            if ("object".equals(type)) {
                Map properties = (Map) fieldDef.get("properties")
                return properties
                        ? buildJsonObjectSchema(properties)
                        : JsonObjectSchema.builder().build()
            }
            Closure<JsonSchemaElement> builderClosure = TYPE_BUILDERS.get(type)
            if (builderClosure != null) return builderClosure.call()
            logger.warn("AiFacade unknown schema type '${type}', defaulting to string")
            return new JsonStringSchema()
        }
    }
}
