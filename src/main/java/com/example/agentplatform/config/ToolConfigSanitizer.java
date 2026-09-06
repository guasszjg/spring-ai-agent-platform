package com.example.agentplatform.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class ToolConfigSanitizer {

    private static final Logger log = LoggerFactory.getLogger(ToolConfigSanitizer.class);
    private static final Set<String> SECRET_FIELDS = Set.of(
            "apikey", "bochaapikey", "authorization", "token", "secret", "password"
    );

    private final ObjectMapper objectMapper = new ObjectMapper();

    public String sanitize(String json) {
        if (json == null || json.isBlank()) {
            return json;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            removeSecrets(root);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("Discarding invalid tool configuration JSON");
            return null;
        }
    }

    private void removeSecrets(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            List<String> remove = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String normalized = field.getKey().replace("_", "").replace("-", "")
                        .toLowerCase(Locale.ROOT);
                if (SECRET_FIELDS.contains(normalized)) {
                    remove.add(field.getKey());
                } else {
                    removeSecrets(field.getValue());
                }
            }
            remove.forEach(object::remove);
        } else if (node.isArray()) {
            node.forEach(this::removeSecrets);
        }
    }
}
