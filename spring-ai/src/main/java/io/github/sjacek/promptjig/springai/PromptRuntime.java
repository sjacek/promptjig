package io.github.sjacek.promptjig.springai;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.sjacek.promptjig.core.ContractResponseValidator;
import io.github.sjacek.promptjig.core.PromptContract;
import io.github.sjacek.promptjig.core.ResponseViolation;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import lombok.Builder;
import lombok.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.template.ValidationMode;
import org.springframework.ai.template.TemplateRenderer;
import org.springframework.ai.template.st.StTemplateRenderer;
import org.springframework.core.io.ByteArrayResource;

/**
 * Stateless rendering and typed reading. Never calls a model, logs data or writes audit records.
 * Instances can be shared across threads when their binding is thread-safe; both built-in bindings are.
 * @param binding thread-safe strict binding matching the generated models' Jackson annotations
 */
@Builder
public record PromptRuntime(@NonNull PromptJsonBinding binding) {
    /**
     * Renders validated templates with named input and reserved contract metadata.
     * Input is checked without repairs. Omitted optional attributes are supplied as null to ST4;
     * extra input keys cannot replace the reserved contract attributes. Each call has its own renderer.
     * @param contract registered contract snapshot
     * @param input generated input model or equivalent schema-valid object
     * @return ordered system/user messages; caller owns model options and audit
     * @throws IllegalArgumentException if input fails schema validation or rendering
     */
    public List<Message> prompt(@NonNull RuntimePromptContract contract, @NonNull Object input) {
        var source = contract.contract();
        try {
            JsonNode node = PromptContract.JSON.readTree(binding.write(input));
            var validation = ContractResponseValidator.builder().schema(source.document().path("input").path("schema"))
                    .build().read(node.toString(), true);
            if (!validation.accepted()) throw new IllegalArgumentException();
            Map<String, @Nullable Object> parameters = new LinkedHashMap<>();
            source.document().path("input").path("schema").path("properties").fieldNames()
                    .forEachRemaining(name -> parameters.put(name, null));
            parameters.putAll(PromptContract.JSON.convertValue(node, new com.fasterxml.jackson.core.type.TypeReference<Map<String, @Nullable Object>>() {}));
            parameters.put("contract", metadata(source));
            // Core has already checked the ST4 AST. Spring's lexical check misidentifies separators and local variables.
            var stRenderer = StTemplateRenderer.builder().validationMode(ValidationMode.NONE).build();
            // TemplateRenderer explicitly accepts nullable attributes; createMessage(Map) excludes them in its API.
            // Bind this call's named input at the renderer boundary so omitted optional fields remain valid ST4 nulls.
            TemplateRenderer renderer = (template, ignoredVariables) -> stRenderer.apply(template, parameters);
            List<Message> messages = new ArrayList<>();
            for (var message : source.document().path("messages")) {
                var resource = new ByteArrayResource(source.templates().get(message.path("template").asText()).getBytes(StandardCharsets.UTF_8));
                messages.add(message.path("role").asText().equals("system")
                        ? SystemPromptTemplate.builder().resource(resource).renderer(renderer).build().createMessage()
                        : PromptTemplate.builder().resource(resource).renderer(renderer).build().createMessage());
            }
            return List.copyOf(messages);
        } catch (Exception exception) {
            // Serialization/template exceptions can quote source data. Never retain them in the public failure.
            throw new IllegalArgumentException("Prompt input or rendering is invalid");
        }
    }

    /**
     * Reads a response only after the caller has durably audited its raw content.
     * Schema rejection never invokes binding. Binding receives only a schema-valid normalized object;
     * a binding exception, null return or incompatible type rejects the result without exposing its cause.
     * Since 0.2.0, omitted output.strict and explicit true reject every schema violation.
     * Only explicit output.strict: false permits repairs. Such accepted results may contain repair findings
     * and still require application audit and human review. Backend json_schema strictness is independent.
     * @param contract registered contract
     * @param rawJson complete JSON response
     * @param type generated output type
     * @param <T> output model
     * @return accepted typed output or safe rejection findings
     */
    public <T> PromptReadResult<T> read(@NonNull RuntimePromptContract contract, @NonNull String rawJson, @NonNull Class<T> type) {
        var source = contract.contract();
        var result = ContractResponseValidator.builder().schema(source.outputSchema()).build()
                // Do not inject this default into the authored document: its fingerprint must stay unchanged.
                .read(rawJson, source.document().path("output").path("strict").asBoolean(true));
        var normalized = result.normalized();
        if (normalized == null) return PromptReadResult.<T>builder().violations(result.violations()).build();
        try {
            var decoded = binding.read(normalized.toString(), type);
            if (decoded == null) throw new IllegalArgumentException();
            T value = type.cast(decoded);
            return PromptReadResult.<T>builder().value(value).violations(result.violations()).build();
        } catch (Exception exception) {
            // Keep earlier repairs as evidence even if conversion of the final tree fails.
            var findings = new ArrayList<>(result.violations());
            findings.add(ResponseViolation.builder().pointer("").rule("binding").action(ResponseViolation.Action.REJECTED)
                    .valueType("object").valueLength(normalized.size()).build());
            return PromptReadResult.<T>builder().violations(findings).build();
        }
    }

    /**
     * Supplies the bare output schema; caller adds the backend's json_schema envelope and options.
     * Uses the same canonical representation as the generated OUTPUT_SCHEMA_JSON constant.
     * @param contract registered contract
     * @return JSON Schema object as JSON
     */
    public String responseFormat(@NonNull RuntimePromptContract contract) { return contract.contract().outputSchemaJson(); }

    private static Map<String, Object> metadata(PromptContract source) {
        var schema = source.outputSchema();
        Map<String, Object> enums = new LinkedHashMap<>();
        Map<String, Object> enumText = new LinkedHashMap<>();
        var properties = schema.path("properties");
        TreeSet<String> fields = new TreeSet<>(); properties.fieldNames().forEachRemaining(fields::add);
        for (String field : fields) {
            JsonNode property = resolve(schema, properties.path(field));
            // Nullable arrays still expose their item enums; v1 places the concrete type before "null".
            JsonNode type = property.path("type");
            if ((type.isArray() ? type.path(0).asText() : type.asText()).equals("array"))
                property = resolve(schema, property.path("items"));
            if (property.has("enum")) {
                List<String> values = new ArrayList<>();
                property.path("enum").forEach(value -> { if (!value.isNull()) values.add(value.asText()); });
                enums.put(field, List.copyOf(values)); enumText.put(field, String.join(", ", values));
            }
        }
        return Map.of("output", Map.of("fields", List.copyOf(fields), "fieldText", String.join(", ", fields), "enums", enums, "enumText", enumText));
    }
    private static JsonNode resolve(JsonNode root, JsonNode node) {
        // Core validation has rejected missing targets and alias-only cycles before a snapshot reaches rendering.
        while (node.has("$ref")) node = root.at(java.net.URI.create(node.path("$ref").asText()).getFragment());
        return node;
    }
}
