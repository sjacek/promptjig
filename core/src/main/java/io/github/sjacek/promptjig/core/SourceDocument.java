package io.github.sjacek.promptjig.core;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLParser;
import java.io.IOException;
import java.io.Serial;
import java.util.HashMap;
import java.util.Map;

/** Reads one UTF-8 YAML/JSON document without losing key and value coordinates. */
final class SourceDocument {
    static final class Problem extends IOException {
        @Serial
        private static final long serialVersionUID = 1L;
        final int line;
        final int column;
        final String reason;

        Problem(JsonParser parser, String reason) {
            super(reason);
            this.reason = reason;
            var location = parser.currentTokenLocation();
            line = Math.max(1, location.getLineNr());
            column = Math.max(1, location.getColumnNr());
        }
    }

    private static final ObjectMapper YAML = new ObjectMapper(YAMLFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    final JsonNode root;
    final String file;
    private final Map<String, int[]> positions = new HashMap<>();

    private SourceDocument(String file, JsonParser parser) throws IOException {
        this.file = file;
        if (parser.nextToken() == null) throw new Problem(parser, "Empty document");
        root = node(parser, "");
        if (parser.nextToken() != null) throw new Problem(parser, "Expected exactly one document");
    }

    static SourceDocument read(String file, String text) throws IOException {
        try (JsonParser parser = YAML.createParser(text)) {
            return new SourceDocument(file, parser);
        }
    }

    private JsonNode node(JsonParser parser, String pointer) throws IOException {
        var location = parser.currentTokenLocation();
        positions.put(pointer, new int[]{location.getLineNr(), location.getColumnNr()});
        if (parser instanceof YAMLParser yaml && yaml.isCurrentAlias())
            throw new Problem(parser, "YAML aliases are not supported; use local schema references");
        var nodes = JsonNodeFactory.instance;
        if (parser.currentToken() == JsonToken.START_OBJECT) {
            var result = nodes.objectNode();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) throw new IOException("Expected a field");
                String name = parser.currentName();
                var key = parser.currentTokenLocation();
                String child = pointer + "/" + escape(name);
                if (parser.nextToken() == null) throw new IOException("Missing field value");
                result.set(name, node(parser, child));
                positions.put(child, new int[]{key.getLineNr(), key.getColumnNr()});
            }
            return result;
        }
        if (parser.currentToken() == JsonToken.START_ARRAY) {
            var result = nodes.arrayNode();
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                if (parser.currentToken() == null) throw new IOException("Unclosed array");
                result.add(node(parser, pointer + "/" + result.size()));
            }
            return result;
        }
        return switch (parser.currentToken()) {
            case VALUE_STRING -> nodes.textNode(parser.getText());
            case VALUE_NUMBER_INT -> nodes.numberNode(parser.getBigIntegerValue());
            case VALUE_NUMBER_FLOAT -> nodes.numberNode(parser.getDecimalValue());
            case VALUE_TRUE -> nodes.booleanNode(true);
            case VALUE_FALSE -> nodes.booleanNode(false);
            case VALUE_NULL -> nodes.nullNode();
            default -> throw new IOException("Unsupported YAML value");
        };
    }

    Diagnostic error(String rule, String pointer, String message) {
        String nearest = pointer;
        while (!positions.containsKey(nearest) && nearest.contains("/"))
            nearest = nearest.substring(0, nearest.lastIndexOf('/'));
        int[] position = positions.getOrDefault(nearest, new int[]{1, 1});
        return new Diagnostic(rule, Diagnostic.Severity.ERROR, file, position[0], position[1], pointer, message);
    }

    static String escape(String name) {
        return name.replace("~", "~0").replace("/", "~1");
    }
}
