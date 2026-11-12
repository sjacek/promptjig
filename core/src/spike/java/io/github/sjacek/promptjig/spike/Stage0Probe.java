package io.github.sjacek.promptjig.spike;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.sjacek.promptjig.core.PromptContract;
import io.github.sjacek.promptjig.core.SchemaValidation;
import io.github.sjacek.promptjig.core.TemplateParameters;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.template.ValidationMode;
import org.springframework.ai.template.st.StTemplateRenderer;
import org.springframework.core.io.FileSystemResource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static io.github.sjacek.promptjig.core.PromptContract.JSON;

/** Reproducible offline evidence. Every payload comes from synthetic resources. */
public final class Stage0Probe {
    private Stage0Probe() { }

    public static void main(String[] args) throws Exception {
        long start = System.nanoTime();
        Path project = Path.of(args[0]);
        Path resources = project.resolve("core/src/spike/resources");
        Path promptDirectory = resources.resolve("prompts/patient/disability");
        PromptContract contract = PromptContract.load(promptDirectory.resolve("certificate-extraction.prompt.yaml"));
        if (!contract.parameterViolations().isEmpty()) throw new IllegalStateException(contract.parameterViolations().toString());
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("java", System.getProperty("java.version"));
        report.put("fingerprint", contract.fingerprint());
        report.put("parameterViolations", contract.parameterViolations());
        Map<String, Object> parameters = new LinkedHashMap<>();
        contract.templates().forEach((name, template) -> parameters.put(name,
                TemplateParameters.read(template).stream().sorted().toList()));
        report.put("parameters", parameters);
        Path e1Path = resources.resolve("prompts/e1/certificate-extraction.st");
        report.put("e1Snapshot", Map.of("parameters", TemplateParameters.read(Files.readString(e1Path)),
                "rawSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(e1Path))),
                "outputFieldCount", contract.outputSchema().path("properties").size()));
        Map<String, List<String>> enums = new LinkedHashMap<>();
        List<String> fields = new ArrayList<>();
        contract.outputSchema().path("properties").properties().forEach(field -> {
            fields.add(field.getKey());
            JsonNode schema = field.getValue();
            JsonNode values = schema.has("items") ? schema.path("items").path("enum") : schema.path("enum");
            List<String> options = new ArrayList<>();
            values.forEach(value -> { if (!value.isNull()) options.add(value.asText()); });
            if (!options.isEmpty()) enums.put(field.getKey(), options);
        });
        Map<String, Object> variables = Map.of("ocrText", Files.readString(resources.resolve("fixtures/ocr.txt")),
                "contract", Map.of("output", Map.of("enums", enums, "fields", fields)));
        var renderer = StTemplateRenderer.builder().validationMode(ValidationMode.THROW).build();
        List<Map<String, Object>> renders = new ArrayList<>();
        List<Map<String, String>> messages = new ArrayList<>();
        for (JsonNode message : contract.document().path("messages")) {
            String name = message.path("template").asText();
            var resource = new FileSystemResource(promptDirectory.resolve(name));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("template", name);
            try {
                String rendered = message.path("role").asText().equals("system")
                        ? SystemPromptTemplate.builder().resource(resource).renderer(renderer).build().render(variables)
                        : PromptTemplate.builder().resource(resource).renderer(renderer).build().render(variables);
                result.put("status", "PASS");
                result.put("renderedLength", rendered.length());
                messages.add(Map.of("role", message.path("role").asText(), "content", rendered));
            } catch (IllegalStateException exception) {
                result.put("status", "FAIL");
                result.put("reason", exception.getMessage());
            }
            renders.add(result);
        }
        report.put("springAiThrow", renders);

        // Separate candidate: remove ST options from authored templates, keep THROW and Resource loading.
        Map<String, Object> enumText = new LinkedHashMap<>();
        enums.forEach((key, value) -> enumText.put(key, String.join(", ", value)));
        Map<String, Object> compatibleVariables = Map.of("contract", Map.of("output",
                Map.of("enumText", enumText, "fieldText", String.join(", ", fields))));
        String compatibleSystem = SystemPromptTemplate.builder()
                .resource(new FileSystemResource(promptDirectory.resolve("certificate-extraction.compatible.st")))
                .renderer(renderer).build().render(compatibleVariables);
        if (!compatibleSystem.contains("PZON_CHILD, PZON_ADULT_DEGREE, ZUS_WORK_INCAPACITY")
                || !compatibleSystem.contains("MILD, MODERATE, SEVERE") || compatibleSystem.contains("{contract")) {
            throw new IllegalStateException("Contract values were not rendered correctly");
        }
        report.put("compatibleCandidate", Map.of("status", "PASS", "renderedLength", compatibleSystem.length()));
        List<Map<String, String>> rendererLimitations = new ArrayList<>();
        try {
            PromptTemplate.builder().resource(new FileSystemResource(project.resolve(
                    "core/src/test/resources/prompts/parameters/anonymous.st"))).renderer(renderer).build()
                    .render(Map.of("values", List.of(Map.of("name", "synthetic")), "suffix", "synthetic"));
            rendererLimitations.add(Map.of("case", "anonymous-local-variables", "status", "PASS"));
        } catch (IllegalStateException exception) {
            rendererLimitations.add(Map.of("case", "anonymous-local-variables", "status", "FAIL", "reason", exception.getMessage()));
        }
        report.put("rendererLimitations", rendererLimitations);
        // Only the synthetic candidate is used for the transport spike, not an application prompt.
        messages.removeIf(message -> message.get("role").equals("system"));
        messages.addFirst(Map.of("role", "system", "content", compatibleSystem));

        ObjectNode valid = (ObjectNode) JSON.readTree(resources.resolve("fixtures/valid-output.json").toFile());
        var schema = SchemaValidation.compile(contract.outputSchema());
        if (!SchemaValidation.validate(schema, valid).isEmpty()) throw new IllegalStateException("Valid fixture rejected");
        List<Map<String, Object>> cases = new ArrayList<>();
        ObjectNode bad = valid.deepCopy();
        bad.put("degree", "UNRECOGNIZED");
        bad.put("issuedAt", "2026-02-30");
        bad.put("permanent", "true");
        bad.putArray("symbolCodes").add("BAD").add("05-R").add(42);
        bad.remove("patientSurname");
        bad.put("unknown/key~name", "synthetic");
        cases.add(validationCase("mixed", SchemaValidation.validate(schema, bad)));
        ObjectNode structural = valid.deepCopy();
        structural.put("symbolCodes", "05-R");
        cases.add(validationCase("array-structure", SchemaValidation.validate(schema, structural)));
        cases.add(validationCase("wrong-root", SchemaValidation.validate(schema, JSON.createArrayNode())));
        // Nested local $ref and escaped keys: errors must point to instance nodes, not $defs.
        JsonNode nestedSchema = JSON.readTree(resources.resolve("fixtures/nested-schema.json").toFile());
        JsonNode nested = JSON.readTree(resources.resolve("fixtures/nested-invalid.json").toFile());
        cases.add(validationCase("nested-ref", SchemaValidation.validate(SchemaValidation.compile(nestedSchema), nested)));
        report.put("validation", cases);
        report.put("elapsedMillis", (System.nanoTime() - start) / 1_000_000);
        Path output = project.resolve("core/build/reports/stage0");
        Files.createDirectories(output);
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve("offline.json").toFile(), report);
        ObjectNode request = JSON.createObjectNode();
        request.set("messages", JSON.valueToTree(messages));
        request.put("temperature", 0);
        request.put("max_tokens", 4096);
        request.put("stream", false);
        request.putObject("response_format").put("type", "json_schema")
                .putObject("json_schema").put("name", "certificate_extraction").put("strict", true)
                .set("schema", contract.outputSchema());
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve("p4-request.json").toFile(), request);
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve("output-schema.json").toFile(), contract.outputSchema());
        Files.writeString(output.resolve("offline.txt"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardCharsets.UTF_8);
        System.out.println("Offline evidence: core/build/reports/stage0/offline.json (" + report.get("elapsedMillis") + " ms)");
    }

    private static Map<String, Object> validationCase(String name, List<com.networknt.schema.Error> errors) {
        List<Map<String, Object>> locations = new ArrayList<>();
        for (var error : errors) {
            Map<String, Object> location = new LinkedHashMap<>();
            location.put("keyword", error.getKeyword());
            location.put("instancePointer", error.getInstanceLocation().toString());
            location.put("schemaLocation", error.getSchemaLocation().toString());
            if (error.getProperty() != null) location.put("property", error.getProperty());
            locations.add(location);
        }
        return Map.of("name", name, "errors", locations);
    }
}
