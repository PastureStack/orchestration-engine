package io.cattle.platform.jmx;

import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class JmxTransJsonParser {

    private final ObjectMapper objectMapper = new ObjectMapper();

    List<JmxQuery> parse(List<URL> resources) throws Exception {
        if (resources == null || resources.isEmpty()) {
            return Collections.emptyList();
        }

        List<JmxQuery> result = new ArrayList<JmxQuery>();
        for (URL resource : resources) {
            try (InputStream inputStream = resource.openStream()) {
                JsonNode root = objectMapper.readTree(inputStream);
                JsonNode queries = root.get("queries");
                if (queries == null || !queries.isArray()) {
                    continue;
                }
                for (JsonNode queryNode : queries) {
                    JmxQuery query = parseQuery(queryNode);
                    if (query != null) {
                        result.add(query);
                    }
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    private JmxQuery parseQuery(JsonNode queryNode) {
        String objectName = text(queryNode.get("objectName"));
        if (EmbeddedJmxTransPublisher.isBlank(objectName)) {
            return null;
        }

        List<JmxAttributeQuery> attributes = parseAttributes(queryNode.get("attributes"));
        if (attributes.isEmpty()) {
            return null;
        }

        return new JmxQuery(objectName, text(queryNode.get("resultAlias")), attributes);
    }

    private List<JmxAttributeQuery> parseAttributes(JsonNode attributesNode) {
        if (attributesNode == null || !attributesNode.isArray()) {
            return Collections.emptyList();
        }

        List<JmxAttributeQuery> attributes = new ArrayList<JmxAttributeQuery>();
        for (JsonNode attributeNode : attributesNode) {
            if (attributeNode.isTextual()) {
                attributes.add(new JmxAttributeQuery(attributeNode.asText(), null));
            } else if (attributeNode.isObject()) {
                String name = text(attributeNode.get("name"));
                if (!EmbeddedJmxTransPublisher.isBlank(name)) {
                    attributes.add(new JmxAttributeQuery(name, parseKeys(attributeNode.get("keys")),
                            text(attributeNode.get("resultAlias"))));
                }
            }
        }
        return attributes;
    }

    private List<String> parseKeys(JsonNode keysNode) {
        if (keysNode == null || !keysNode.isArray()) {
            return Collections.emptyList();
        }

        List<String> keys = new ArrayList<String>();
        for (JsonNode keyNode : keysNode) {
            if (keyNode.isTextual()) {
                keys.add(keyNode.asText());
            }
        }
        return keys;
    }

    private String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }
}
