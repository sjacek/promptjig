package io.github.sjacek.promptjig.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Semantic checks beyond the structural v1 schema. Local references never trigger network access. */
final class SchemaSubset {
    private SchemaSubset() { }

    static void check(SourceDocument document, String base, List<Diagnostic> diagnostics) {
        JsonNode root = document.root.at(base);
        Map<String, JsonNode> nodes = new HashMap<>();
        index(root, "", nodes);
        for (var entry : nodes.entrySet()) {
            String pointer = base + entry.getKey();
            JsonNode schema = entry.getValue();
            if (schema.has("$ref")) {
                if (schema.size() != 1) error(document, diagnostics, pointer,
                        "A local $ref must be the only keyword of its schema");
                Set<String> visited = new HashSet<>();
                JsonNode target = schema;
                // Follow alias-only chains. Reaching a concrete schema is enough: recursive objects are valid.
                while (target.has("$ref")) {
                    String reference = target.path("$ref").asText();
                    String fragment;
                    try { fragment = URI.create(reference).getFragment(); }
                    catch (IllegalArgumentException exception) { fragment = null; }
                    if (fragment == null || !nodes.containsKey(fragment)) {
                        error(document, diagnostics, pointer + "/$ref", "Reference must resolve to a local schema in $defs");
                        break;
                    }
                    if (!visited.add(fragment)) {
                        error(document, diagnostics, pointer + "/$ref", "Reference alias cycle has no concrete schema");
                        break;
                    }
                    target = nodes.get(fragment);
                }
                continue;
            }
            String type = type(schema);
            if (type.equals("object")) {
                if (!schema.has("properties") || !schema.path("additionalProperties").isBoolean()
                        || schema.path("additionalProperties").asBoolean(true))
                    error(document, diagnostics, pointer, "Objects require properties and additionalProperties: false");
                for (JsonNode required : schema.path("required")) {
                    if (!schema.path("properties").has(required.asText())) error(document, diagnostics,
                            pointer + "/required", "Required property is not declared in properties: " + required.asText());
                }
            } else if (schema.has("properties") || schema.has("required") || schema.has("additionalProperties")) {
                error(document, diagnostics, pointer, "Object keywords require type: object");
            }
            if (type.equals("array") != schema.has("items"))
                error(document, diagnostics, pointer, "Arrays require items; items is only supported on arrays");
            if (schema.has("pattern") || schema.has("format")) {
                if (!type.equals("string")) error(document, diagnostics, pointer, "pattern and format require type: string");
                if (schema.has("pattern")) {
                    try { Pattern.compile(schema.path("pattern").asText()); }
                    catch (PatternSyntaxException exception) {
                        error(document, diagnostics, pointer + "/pattern", "Invalid regular expression");
                    }
                }
            }
            for (JsonNode value : schema.path("enum")) {
                if (!matches(schema, value)) error(document, diagnostics, pointer + "/enum",
                        "Every enum value must match the declared type, including nullability");
            }
        }
    }

    private static void index(JsonNode schema, String pointer, Map<String, JsonNode> nodes) {
        nodes.put(pointer, schema);
        for (String keyword : List.of("properties", "$defs")) schema.path(keyword).properties().forEach(entry ->
                index(entry.getValue(), pointer + "/" + keyword + "/" + SourceDocument.escape(entry.getKey()), nodes));
        if (schema.has("items")) index(schema.path("items"), pointer + "/items", nodes);
    }

    private static String type(JsonNode schema) {
        JsonNode type = schema.path("type");
        return type.isArray() ? type.path(0).asText() : type.asText();
    }

    private static boolean matches(JsonNode schema, JsonNode value) {
        if (value.isNull()) return schema.path("type").isArray();
        return switch (type(schema)) {
            case "string" -> value.isTextual();
            case "boolean" -> value.isBoolean();
            case "integer" -> value.isNumber() && value.decimalValue().stripTrailingZeros().scale() <= 0;
            case "number" -> value.isNumber();
            default -> false;
        };
    }

    private static void error(SourceDocument document, List<Diagnostic> diagnostics, String pointer, String message) {
        diagnostics.add(document.error("schema-subset", pointer, message));
    }
}
