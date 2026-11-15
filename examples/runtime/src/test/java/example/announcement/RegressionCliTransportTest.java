package example.announcement;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives the explicit local CLI through the real SDK against a loopback stub.
 * The stub uses reviewed fictional answers; this verifies transport/report provenance, not model quality.
 */
@Timeout(45)
class RegressionCliTransportTest {
    @TempDir Path directory;

    @Test
    void invalidReportDestinationFailsBeforeAnyProviderRequest() throws Exception {
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        var file = directory.resolve("existing-file-testdata");
        Files.writeString(file, "Preserve this caller-owned file.");
        var environment = Map.of("PROMPTJIG_MODEL", LocalModelSettingsTest.DIGEST,
                "PROMPTJIG_BASE_URL", "http://127.0.0.1:" + server.getAddress().getPort() + "/engines/v1");
        try (var output = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)) {
            assertThrows(java.io.IOException.class, () -> PilotApplication.run(
                    new String[]{"--local-regression-report=" + file}, environment, output));
        } finally {
            server.stop(0);
        }
        assertEquals(0, calls.get(), "Do not spend model time when the report cannot be saved");
        assertEquals("Preserve this caller-owned file.", Files.readString(file));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cliReportsAllIndependentCallsAndKeepsEvidenceAfterHttp429WithoutRetry(boolean firstRequestFails) throws Exception {
        var corpus = AnnouncementRegressionCorpus.load();
        var mapper = JsonMapper.builder().build();
        var calls = new AtomicInteger();
        var requests = new ConcurrentLinkedQueue<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/engines/v1/chat/completions", exchange -> {
            int index = calls.getAndIncrement();
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            boolean failure = firstRequestFails && index == 0;
            // A rate limit is an unsuccessful case, not permission to replay this request or fabricate a response.
            byte[] reply = failure ? mapper.writeValueAsBytes(Map.of("error", Map.of("message", "private-provider-body-testdata", "type", "rate_limit")))
                    : mapper.writeValueAsBytes(Map.of("id", "regression-completion-testdata", "object", "chat.completion", "created", 0,
                            "model", LocalModelSettingsTest.DIGEST, "choices", List.of(Map.of("index", 0, "finish_reason", "stop",
                                    "message", Map.of("role", "assistant", "content", corpus.cases().get(index).expectedJson()))),
                            "usage", Map.of("prompt_tokens", 100, "completion_tokens", 40, "total_tokens", 140)));
            try {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(failure ? 429 : 200, reply.length);
                exchange.getResponseBody().write(reply);
            } finally {
                exchange.close();
            }
        });
        server.start();
        var bytes = new ByteArrayOutputStream();
        var destination = directory.resolve("local-report");
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/engines/v1";
        var environment = Map.of("PROMPTJIG_MODEL", LocalModelSettingsTest.DIGEST, "PROMPTJIG_BASE_URL", baseUrl,
                "PROMPTJIG_TIMEOUT_SECONDS", "5", "PROMPTJIG_MAX_TOKENS", "4096");
        try (var output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            String[] args = {"--local-regression-report=" + destination};
            if (firstRequestFails) assertThrows(IllegalStateException.class, () -> PilotApplication.run(args, environment, output));
            else PilotApplication.run(args, environment, output);
            output.println("caller-stream-open-testdata");
            assertFalse(output.checkError());
        } finally {
            server.stop(0);
        }

        assertEquals(25, calls.get(), "Exactly one request per case, including the failed case");
        var wireRequests = List.copyOf(requests);
        for (int index = 0; index < wireRequests.size(); index++) {
            var wire = mapper.readTree(wireRequests.get(index));
            assertNotNull(wire);
            assertEquals(LocalModelSettingsTest.DIGEST, wire.path("model").asString());
            assertEquals(0.0, wire.required("temperature").asDouble());
            assertEquals(4096, wire.has("max_completion_tokens")
                    ? wire.required("max_completion_tokens").asInt() : wire.required("max_tokens").asInt());
            assertEquals(2, wire.path("messages").size(), "No previous case is appended to the conversation");
            assertTrue(wire.at("/messages/1/content").asString().replace("\r\n", "\n")
                    .contains(corpus.cases().get(index).document().strip()));
        }

        String raw = Files.readString(destination.resolve("quality-report.json"));
        String markdown = Files.readString(destination.resolve("quality-report.md"));
        var report = mapper.readTree(raw);
        assertNotNull(report);
        int successful = firstRequestFails ? 24 : 25;
        assertEquals(25, report.path("cases").size());
        assertEquals(successful, report.path("summary").path("passedCases").asInt());
        assertEquals(successful * 5, report.path("summary").path("matchingFields").asInt());
        assertEquals(125, report.path("summary").path("totalFields").asInt());
        assertEquals("LOCAL_MODEL", report.at("/context/mode").asString());
        assertEquals(LocalModelSettingsTest.DIGEST, report.at("/context/settings/model").asString());
        assertEquals(baseUrl, report.at("/context/settings/baseUrl").asString());
        assertEquals(4096, report.at("/context/settings/maxTokens").asInt());
        assertEquals(0, report.at("/context/maxRetries").asInt());
        assertEquals(successful, report.at("/usage/totalTokens/reportedCases").asInt());
        assertEquals(successful * 140, report.at("/usage/totalTokens/total").asInt());
        assertEquals(corpus.sha256(), report.path("corpusSha256").asString());
        assertTrue(markdown.contains("not general model accuracy"));
        assertFalse((raw + markdown + bytes.toString(StandardCharsets.UTF_8)).contains("private-provider-body-testdata"));
        if (firstRequestFails) {
            assertEquals("PROVIDER_ERROR", report.at("/cases/0/outcome").asString());
            assertTrue(report.at("/cases/0/schemaAccepted").isNull());
            assertTrue(report.at("/cases/0/diagnostics").isNull());
            assertEquals("PASSED", report.at("/cases/24/outcome").asString());
        }
    }
}
