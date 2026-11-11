package io.github.sjacek.promptjig.spike;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.sjacek.promptjig.core.SchemaValidation;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.template.ValidationMode;
import org.springframework.ai.template.st.StTemplateRenderer;
import org.springframework.core.io.FileSystemResource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static io.github.sjacek.promptjig.core.PromptContract.JSON;

/** Explicit synthetic transport experiment, never part of check. */
public final class ModelRunnerProbe {
    private ModelRunnerProbe() { }

    public static void main(String[] args) throws Exception {
        Path project = Path.of(args[0]);
        String model = args[1];
        int timeoutSeconds = Integer.parseInt(args[2]);
        if (timeoutSeconds < 1 || timeoutSeconds > 1800) throw new IllegalArgumentException("Timeout must be 1..1800 seconds");
        if (!model.matches("sha256:[0-9a-f]{64}")) throw new IllegalArgumentException("Use a pinned sha256 model ID");
        Path output = project.resolve("core/build/reports/stage0");
        Path resources = project.resolve("core/src/spike/resources");
        ObjectNode certificateRequest = (ObjectNode) JSON.readTree(output.resolve("p4-request.json").toFile());
        JsonNode certificateSchema = JSON.readTree(output.resolve("output-schema.json").toFile());
        ObjectNode constraintRequest = certificateRequest.deepCopy();
        var messages = constraintRequest.putArray("messages");
        var renderer = StTemplateRenderer.builder().validationMode(ValidationMode.THROW).build();
        messages.addObject().put("role", "system").put("content", SystemPromptTemplate.builder()
                .resource(new FileSystemResource(resources.resolve("prompts/p4/constraint.system.st")))
                .renderer(renderer).build().render(Map.of()));
        messages.addObject().put("role", "user").put("content", PromptTemplate.builder()
                .resource(new FileSystemResource(resources.resolve("prompts/p4/constraint.user.st")))
                .renderer(renderer).build().render(Map.of()));
        constraintRequest.put("max_tokens", 1024);
        JsonNode constraintSchema = JSON.readTree(resources.resolve("fixtures/constraint-schema.json").toFile());
        ((ObjectNode) constraintRequest.path("response_format").path("json_schema")).set("schema", constraintSchema);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("recordedAt", Instant.now().toString());
        report.put("endpoint", "http://localhost:12434/engines/v1/chat/completions");
        report.put("model", model);
        report.put("timeoutSeconds", timeoutSeconds);
        List<Map<String, Object>> cases = new ArrayList<>();
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build()) {
            // Baseline first so a cold model's loading time is not attributed to the schema mode.
            for (String name : List.of("certificate-json-object", "certificate-json-schema", "constraint-json-object", "constraint-json-schema")) {
                boolean certificate = name.startsWith("certificate");
                ObjectNode request = (certificate ? certificateRequest : constraintRequest).deepCopy();
                JsonNode schema = certificate ? certificateSchema : constraintSchema;
                request.put("model", model);
                if (name.endsWith("json-object")) request.putObject("response_format").put("type", "json_object");
                JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve(name + "-request.json").toFile(), request);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("name", name);
                long start = System.nanoTime();
                System.out.println("Running " + name + " with " + model);
                try {
                    HttpResponse<String> response = client.send(HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:12434/engines/v1/chat/completions"))
                            .timeout(Duration.ofSeconds(timeoutSeconds)).header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(request))).build(),
                            HttpResponse.BodyHandlers.ofString());
                    result.put("httpStatus", response.statusCode());
                    Files.writeString(output.resolve(name + "-response.json"), response.body());
                    if (response.statusCode() == 200) {
                        JsonNode envelope = JSON.readTree(response.body());
                        JsonNode choice = envelope.path("choices").path(0);
                        result.put("finishReason", choice.path("finish_reason").asText());
                        result.put("usage", envelope.path("usage"));
                        String content = choice.path("message").path("content").asText();
                        try {
                            JsonNode tree = JSON.readTree(content);
                            var errors = SchemaValidation.compile(schema).validate(tree);
                            result.put("schemaValid", errors.isEmpty());
                            result.put("violations", errors.stream().map(error -> Map.of("keyword", error.getKeyword(),
                                    "instancePointer", error.getInstanceLocation().toString())).toList());
                            if (!certificate) result.put("marker", tree.path("marker").asText());
                        } catch (Exception exception) {
                            result.put("schemaValid", false);
                            result.put("parseFailureType", exception.getClass().getSimpleName());
                        }
                    }
                } catch (java.io.IOException exception) {
                    result.put("transportFailureType", exception.getClass().getSimpleName());
                }
                result.put("elapsedMillis", (System.nanoTime() - start) / 1_000_000);
                cases.add(result);
                report.put("cases", cases);
                JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve("model-runner.json").toFile(), report);
                System.out.println(name + ": " + JSON.writeValueAsString(result));
            }
        }
        System.out.println("P4 evidence: core/build/reports/stage0/model-runner.json");
    }
}
