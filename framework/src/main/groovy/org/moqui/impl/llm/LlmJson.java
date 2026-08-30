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
package org.moqui.impl.llm;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import groovy.lang.GString;
import org.moqui.BaseException;

import java.io.IOException;
import java.util.List;
import java.util.Map;

final class LlmJson {
    static final ObjectMapper mapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .configure(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN, true);
    static {
        SimpleModule module = new SimpleModule();
        module.addSerializer(GString.class, new StdSerializer<GString>(GString.class) {
            @Override public void serialize(GString value, JsonGenerator gen, SerializerProvider serializers)
                    throws IOException, JsonProcessingException {
                if (value != null) gen.writeString(value.toString());
            }
        });
        mapper.registerModule(module);
    }

    private LlmJson() { }

    static String toJson(Object jsonObject) {
        if (jsonObject instanceof String) return (String) jsonObject;
        try {
            return mapper.writeValueAsString(jsonObject);
        } catch (Exception e) {
            throw new BaseException("Error writing JSON", e);
        }
    }

    static Object toObject(String jsonString) {
        if (jsonString == null || jsonString.isBlank()) return null;
        try {
            JsonNode jsonNode = mapper.readTree(jsonString);
            if (jsonNode == null || jsonNode.isNull()) return null;
            if (jsonNode.isObject()) return mapper.treeToValue(jsonNode, Map.class);
            if (jsonNode.isArray()) return mapper.treeToValue(jsonNode, List.class);
            if (jsonNode.isTextual()) return jsonNode.asText();
            if (jsonNode.isNumber()) return jsonNode.numberValue();
            if (jsonNode.isBoolean()) return jsonNode.booleanValue();
            return mapper.treeToValue(jsonNode, Object.class);
        } catch (Throwable t) {
            throw new BaseException("Error parsing JSON: " + t.toString(), t);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> toMap(String jsonString) {
        Object obj = toObject(jsonString);
        if (obj == null) return null;
        if (obj instanceof Map) return (Map<String, Object>) obj;
        throw new BaseException("JSON text root is not an Object");
    }

    /** Parse object JSON; null/blank → empty map; malformed → null (does not throw). */
    static Map<String, Object> tryToMap(String jsonString) {
        if (jsonString == null || jsonString.isBlank()) return new java.util.LinkedHashMap<>();
        try {
            Map<String, Object> map = toMap(jsonString);
            return map != null ? map : new java.util.LinkedHashMap<>();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
