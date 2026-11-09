package org.testar.asm.playwright;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;

/** Assemble small driver messages and retain already owned source trees. */
final class JsonData {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonData() {
    }

    static ObjectNode object(Object... fields) {
        var values = new LinkedHashMap<String, Object>();
        for (int index = 0; index < fields.length; index += 2) {
            values.put((String) fields[index], fields[index + 1]);
        }
        return MAPPER.valueToTree(values);
    }

    static ArrayNode array() {
        return MAPPER.createArrayNode();
    }

    static ArrayNode array(JsonNode value) {
        return array().add(value);
    }
}
