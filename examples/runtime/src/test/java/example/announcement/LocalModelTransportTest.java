package example.announcement;

import com.sun.net.httpserver.HttpServer;
import example.announcement.generated.AnnouncementExtractionPromptContract;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Real Spring AI/OpenAI serialization and error handling against loopback HTTP; no inference. */
@Timeout(20)
class LocalModelTransportTest {
    @ParameterizedTest
    @ValueSource(ints = {1024, 4096, 8192})
    void sdkSendsTheContractSchemaAndPinnedModelAndReadsTheResponse(int maxTokens) throws Exception {
        var json = JsonMapper.builder().build();
        byte[] reply = json.writeValueAsBytes(Map.of("id", "completion-testdata", "object", "chat.completion", "created", 0,
                "model", LocalModelSettingsTest.DIGEST, "choices", List.of(Map.of("index", 0, "finish_reason", "stop",
                        "message", Map.of("role", "assistant", "content", AnnouncementPilotTest.resource("responses/public-event-testdata.json")))),
                "usage", Map.of("prompt_tokens", 200, "completion_tokens", 84, "total_tokens", 284,
                        "completion_tokens_details", Map.of("reasoning_tokens", 32))));
        var request = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/engines/v1/chat/completions", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
        try (var session = settings(server, maxTokens).open()) {
            var diagnostics = new AtomicReference<ModelReplyDiagnostics>();
            var result = AnnouncementPilot.builder().model(session.model()).build()
                    .extract(AnnouncementPilotTest.resource("documents/public-event-testdata.txt"), diagnostics::set);
            assertTrue(result.accepted(), result.violations().toString());
            assertTrue(result.violations().isEmpty());
            var raw = request.get();
            if (raw == null) throw new AssertionError("No request received");
            var wire = json.readTree(raw);
            if (wire == null) throw new AssertionError("Missing request JSON");
            assertEquals(LocalModelSettingsTest.DIGEST, wire.path("model").asString());
            assertEquals(0.0, wire.required("temperature").asDouble());
            assertEquals(maxTokens, wire.has("max_completion_tokens")
                    ? wire.required("max_completion_tokens").asInt() : wire.required("max_tokens").asInt());
            assertEquals("json_schema", wire.at("/response_format/type").asString());
            assertTrue(wire.at("/response_format/json_schema/strict").asBoolean());
            assertEquals(json.readTree(AnnouncementExtractionPromptContract.OUTPUT_SCHEMA_JSON), wire.at("/response_format/json_schema/schema"));
            assertEquals("system", wire.at("/messages/0/role").asString());
            assertTrue(wire.at("/messages/0/content").asString().contains("IN_PERSON, ONLINE, HYBRID"));
            assertEquals("user", wire.at("/messages/1/role").asString());
            assertTrue(wire.at("/messages/1/content").asString().contains("{example}"));
            var metadata = assertInstanceOf(ModelReplyDiagnostics.class, diagnostics.get());
            assertTrue("stop".equalsIgnoreCase(metadata.finishReason()));
            assertEquals(200, metadata.promptTokens());
            assertEquals(84, metadata.completionTokens());
            assertEquals(284, metadata.totalTokens());
            assertEquals(32L, metadata.reasoningTokens());
            assertFalse(metadata.truncated());
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lengthFinishReasonIsCapturedAndRejectedEvenWithSchemaValidText(boolean completeJson) throws Exception {
        var json = JsonMapper.builder().build();
        String content = completeJson ? AnnouncementPilotTest.resource("responses/public-event-testdata.json") : "";
        byte[] reply = json.writeValueAsBytes(Map.of("id", "truncated-testdata", "object", "chat.completion", "created", 0,
                "model", LocalModelSettingsTest.DIGEST, "choices", List.of(Map.of("index", 0, "finish_reason", "length",
                        "message", Map.of("role", "assistant", "content", content))),
                "usage", Map.of("prompt_tokens", 200, "completion_tokens", 1024, "total_tokens", 1224,
                        "completion_tokens_details", Map.of("reasoning_tokens", 920))));
        var calls = new AtomicInteger();
        var server = responseServer(200, reply, calls);
        var diagnostics = new AtomicReference<ModelReplyDiagnostics>();
        try (var session = settings(server).open()) {
            var pilot = AnnouncementPilot.builder().model(session.model()).build();
            var failure = assertThrows(IllegalStateException.class,
                    () -> pilot.extract("synthetic announcement-testdata", diagnostics::set));
            assertEquals("Model response exceeded the configured token limit", failure.getMessage());
            var metadata = assertInstanceOf(ModelReplyDiagnostics.class, diagnostics.get());
            assertTrue(metadata.truncated());
            assertEquals(1024, metadata.completionTokens());
            assertEquals(920L, metadata.reasoningTokens());
            assertEquals(content.length(), metadata.outputCharacters());
            assertEquals(1, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 429, 503})
    void sdkDoesNotRetryProviderFailures(int status) throws Exception {
        var calls = new AtomicInteger();
        byte[] reply = "{\"error\":{\"message\":\"synthetic unavailable-testdata\",\"type\":\"server_error\"}}".getBytes(StandardCharsets.UTF_8);
        var server = responseServer(status, reply, calls);
        try (var session = settings(server).open()) {
            var pilot = AnnouncementPilot.builder().model(session.model()).build();
            assertThrows(RuntimeException.class, () -> pilot.extract("synthetic announcement-testdata"));
            assertEquals(1, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void httpSuccessWithInvalidAssistantJsonReturnsSchemaRejection() throws Exception {
        var json = JsonMapper.builder().build();
        byte[] reply = json.writeValueAsBytes(Map.of("id", "completion-testdata", "object", "chat.completion", "created", 0,
                "model", LocalModelSettingsTest.DIGEST, "choices", List.of(Map.of("index", 0, "finish_reason", "stop",
                        "message", Map.of("role", "assistant", "content", "invalid-json-testdata")))));
        var calls = new AtomicInteger();
        var server = responseServer(200, reply, calls);
        try (var session = settings(server).open()) {
            var result = AnnouncementPilot.builder().model(session.model()).build().extract("synthetic announcement-testdata");
            assertFalse(result.accepted());
            assertNull(result.value());
            assertFalse(result.violations().isEmpty());
            assertEquals(1, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void invalidProviderEnvelopeFailsBeforeContractReading() throws Exception {
        var calls = new AtomicInteger();
        var server = responseServer(200, "invalid-provider-json-testdata".getBytes(StandardCharsets.UTF_8), calls);
        try (var session = settings(server).open()) {
            var pilot = AnnouncementPilot.builder().model(session.model()).build();
            assertThrows(RuntimeException.class, () -> pilot.extract("synthetic announcement-testdata"));
            assertEquals(1, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void requestTimeoutStopsAStalledBackendWithoutRetries() throws Exception {
        var calls = new AtomicInteger();
        var release = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/engines/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            try {
                // A real stalled response exercises the client's timeout rather than just its settings object.
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        var bounded = LocalModelSettings.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/engines/v1")
                .model(LocalModelSettingsTest.DIGEST).timeout(Duration.ofSeconds(1)).maxTokens(4096).build();
        try (var session = bounded.open()) {
            var pilot = AnnouncementPilot.builder().model(session.model()).build();
            // Allow scheduling margin while still failing well before the stub's ten-second response window.
            assertTimeoutPreemptively(Duration.ofSeconds(5),
                    () -> assertThrows(RuntimeException.class, () -> pilot.extract("synthetic announcement-testdata")));
            assertEquals(1, calls.get());
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    private static HttpServer responseServer(int status, byte[] reply, AtomicInteger calls) throws java.io.IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/engines/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static LocalModelSettings settings(HttpServer server) {
        return settings(server, 4096);
    }

    private static LocalModelSettings settings(HttpServer server, int maxTokens) {
        return LocalModelSettings.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/engines/v1")
                .model(LocalModelSettingsTest.DIGEST).timeout(Duration.ofSeconds(5)).maxTokens(maxTokens).build();
    }
}
