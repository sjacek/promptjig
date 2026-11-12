package io.github.sjacek.promptjig.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Schema;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import static io.github.sjacek.promptjig.core.ResponseViolation.Action.*;

/**
 * Offline, schema-driven response normalization for an already validated v1 object schema.
 * Use contract loading/validation before constructing this reader: it does not validate arbitrary schema dialects.
 * Each read owns its traversal, findings and compiled subtree validators, so instances can be shared safely.
 * @param schema root response schema, copied before use; references stay relative to this root
 */
@Builder
public record ContractResponseValidator(@NonNull JsonNode schema) {
    /**
     * Copies the authored schema, separating it from caller mutations.
     * @param schema validated v1 root object schema
     */
    public ContractResponseValidator { schema = schema.deepCopy(); }

    /** {@return a copy of the configured schema} */
    @Override
    public JsonNode schema() { return schema.deepCopy(); }

    /**
     * Parses one JSON object and applies only the repairs permitted by the contract policy.
     * No exception messages, original values or unknown property names enter the result.
     * Invalid nullable leaves become null, invalid array elements and unknown properties are removed,
     * and missing required nullable fields/arrays are filled. Missing optional fields stay absent.
     * Structural errors and failed final validation reject the whole response without returning a partial tree.
     * This low-level API always requires an explicit policy; the Spring AI runtime resolves output.strict
     * and, since 0.2.0, defaults an omitted setting to true.
     * @param rawJson complete model response, never a markdown envelope
     * @param strict whether every violation rejects the response
     * @return accepted tree and safe findings, or findings with no tree
     */
    public ResponseValidation read(@NonNull String rawJson, boolean strict) {
        JsonNode original;
        try { original = PromptContract.JSON.readTree(rawJson); }
        catch (IOException exception) {
            return ResponseValidation.builder().violations(List.of(ResponseViolation.builder().pointer("")
                    .rule("json").action(REJECTED).valueType("invalid-json").valueLength(rawJson.codePointCount(0, rawJson.length())).build())).build();
        }
        if (original == null || !original.isObject()) {
            return ResponseValidation.builder().violations(List.of(finding("", "type", REJECTED,
                    original == null ? MissingNode.getInstance() : original))).build();
        }
        var walk = new Walk(schema, strict);
        JsonNode normalized = walk.repair(schema, original, "");
        if (!walk.fatal && !(strict && !walk.findings.isEmpty())) {
            // Guard against repairs that satisfy a type union but violate another keyword, including enum(null).
            var remaining = SchemaValidation.validate(walk.validator(schema), normalized);
            if (!remaining.isEmpty()) walk.errors(remaining, normalized, "", REJECTED);
        }
        boolean accepted = !walk.fatal && !(strict && !walk.findings.isEmpty());
        return ResponseValidation.builder().normalized(accepted ? normalized : null).violations(walk.findings).build();
    }

    /** Mutable state belongs to one response, never to the reusable validator or another caller's data. */
    @RequiredArgsConstructor
    private static final class Walk {
        private final JsonNode root;
        private final boolean strict;
        private final List<ResponseViolation> findings = new ArrayList<>();
        private final Map<JsonNode, Schema> validators = new IdentityHashMap<>();
        private boolean fatal;

        private JsonNode resolved(JsonNode schema) {
            while (schema.has("$ref")) schema = root.at(URI.create(schema.path("$ref").asText()).getFragment());
            return schema;
        }

        private Schema validator(JsonNode original) {
            JsonNode target = resolved(original);
            return validators.computeIfAbsent(target, node -> {
                ObjectNode standalone = node.deepCopy();
                // Standalone item/leaf validation must retain references to the contract's global $defs.
                if (root.has("$defs")) standalone.set("$defs", root.path("$defs"));
                return SchemaValidation.compile(standalone);
            });
        }

        // A nullable type alone is insufficient: enum can still exclude null.
        private boolean nullable(JsonNode schema) { return SchemaValidation.validate(validator(schema), NullNode.getInstance()).isEmpty(); }

        private ResponseViolation.Action disposition(ResponseViolation.Action repair) { return strict ? REJECTED : repair; }

        private void add(String pointer, String rule, ResponseViolation.Action action, JsonNode value) {
            findings.add(finding(pointer, rule, disposition(action), value));
            if (action == REJECTED) fatal = true;
        }

        private JsonNode repair(JsonNode originalSchema, JsonNode value, String pointer) {
            JsonNode schema = resolved(originalSchema);
            if (value.isNull() && nullable(schema)) return value;
            String type = schema.path("type").isArray() ? schema.path("type").path(0).asText() : schema.path("type").asText();
            if (type.equals("object")) {
                if (!value.isObject()) { add(pointer, "type", REJECTED, value); return value; }
                ObjectNode object = value.deepCopy();
                JsonNode properties = schema.path("properties");
                for (JsonNode name : schema.path("required")) {
                    String field = name.asText();
                    if (object.has(field)) continue;
                    JsonNode property = resolved(properties.path(field));
                    String propertyType = property.path("type").isArray() ? property.path("type").path(0).asText() : property.path("type").asText();
                    if (propertyType.equals("array")) {
                        add(pointer + "/" + SourceDocument.escape(field), "required", FILLED, MissingNode.getInstance());
                        object.set(field, PromptContract.JSON.createArrayNode());
                    } else if (nullable(property)) {
                        add(pointer + "/" + SourceDocument.escape(field), "required", FILLED, MissingNode.getInstance());
                        object.set(field, NullNode.getInstance());
                    } else add(pointer + "/" + SourceDocument.escape(field), "required", REJECTED, MissingNode.getInstance());
                }
                TreeSet<String> keys = new TreeSet<>(); value.fieldNames().forEachRemaining(keys::add);
                // Traverse only keys from the original object; synthesized required values already have findings.
                for (String field : keys) {
                    if (!properties.has(field)) {
                        // A model can put sensitive data in a key; report only its containing object.
                        add(pointer, "additionalProperties", REMOVED, object.get(field));
                        object.remove(field);
                    } else object.set(field, repair(properties.path(field), object.get(field), pointer + "/" + SourceDocument.escape(field)));
                }
                return object;
            }
            if (type.equals("array")) {
                if (!value.isArray()) { add(pointer, "type", REJECTED, value); return value; }
                ArrayNode array = PromptContract.JSON.createArrayNode();
                for (int index = 0; index < value.size(); index++) {
                    JsonNode item = value.get(index);
                    // An invalid item is discarded as a whole, even when individual leaves could be repaired.
                    // Use the original index for evidence; accepted elements may shift in the normalized array.
                    var invalid = SchemaValidation.validate(validator(schema.path("items")), item);
                    if (invalid.isEmpty()) array.add(repair(schema.path("items"), item, pointer + "/" + index));
                    else errors(invalid, item, pointer + "/" + index, REMOVED);
                }
                return array;
            }
            var invalid = SchemaValidation.validate(validator(schema), value);
            if (invalid.isEmpty()) {
                // JSON Schema integer includes mathematically integral decimal notation, such as 1.0.
                return type.equals("integer") ? PromptContract.JSON.getNodeFactory().numberNode(value.bigIntegerValue()) : value;
            }
            boolean canRepair = nullable(schema);
            errors(invalid, value, pointer, canRepair ? NULLIFIED : REJECTED);
            return canRepair ? NullNode.getInstance() : value;
        }

        private void errors(List<com.networknt.schema.Error> errors, JsonNode value, String prefix, ResponseViolation.Action action) {
            // Translate locations/keywords only: validator messages and arguments may quote model-provided content.
            for (var error : errors) {
                String pointer = error.getInstanceLocation().toString();
                JsonNode affected = value.at(pointer);
                if (error.getKeyword().equals("required")) {
                    pointer += "/" + SourceDocument.escape(error.getProperty());
                    affected = MissingNode.getInstance();
                } else if (error.getKeyword().equals("additionalProperties")) {
                    affected = affected.path(error.getProperty());
                }
                add(prefix + pointer, error.getKeyword(), action, affected);
            }
        }
    }

    private static ResponseViolation finding(String pointer, String rule, ResponseViolation.Action action, JsonNode value) {
        String type = value.isMissingNode() ? "missing" : value.getNodeType().name().toLowerCase(java.util.Locale.ROOT);
        int length = value.isContainerNode() ? value.size() : value.isTextual()
                ? value.asText().codePointCount(0, value.asText().length()) : value.isNull() || value.isMissingNode() ? 0 : value.asText().length();
        return ResponseViolation.builder().pointer(pointer).rule(rule).action(action).valueType(type).valueLength(length).build();
    }
}
