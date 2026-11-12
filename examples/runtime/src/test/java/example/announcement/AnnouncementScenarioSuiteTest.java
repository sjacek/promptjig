package example.announcement;

import example.announcement.generated.AnnouncementExtractionOutput;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import static org.junit.jupiter.api.Assertions.*;

/** Evaluates suite decisions with deterministic responses; none of these checks invokes inference. */
class AnnouncementScenarioSuiteTest {
    @Test
    void reviewedFixturesCoverOnlinePaidAndUnknownFactsWithoutClosingCallerOutput() throws Exception {
        var bytes = new ByteArrayOutputStream();
        List<AnnouncementScenarioSuite.ScenarioResult> results;
        try (var output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            results = suite((id, reply) -> reply).run(output);
            output.println("caller-owned-stream-testdata");
            assertFalse(output.checkError());
        }
        assertEquals(5, results.size());
        assertTrue(results.stream().allMatch(AnnouncementScenarioSuite.ScenarioResult::passed), results.toString());
        assertThrows(UnsupportedOperationException.class, results::clear);
        var online = actual(results, "online-event-testdata");
        assertEquals(AnnouncementExtractionOutput.Format.ONLINE, online.format());
        assertEquals("https://spotkanie.example.org/mapy", online.location());
        assertEquals(LocalDate.of(2027, 1, 15), online.date());
        assertEquals(Boolean.TRUE, online.free());
        assertEquals(Boolean.FALSE, actual(results, "paid-event-testdata").free());
        var missing = actual(results, "missing-details-testdata");
        assertEquals("Klub pomysłów", missing.title());
        assertNull(missing.date());
        assertNull(missing.location());
        assertNull(missing.format());
        assertNull(missing.free());
        assertNull(actual(results, "missing-price-testdata").free(), "Absent pricing must not imply free admission");
        String evidence = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(evidence.contains("Mapy w przeglądarce"));
        assertTrue(evidence.contains("\"free\":null"), "Evidence must retain unknown facts explicitly");
        assertTrue(evidence.endsWith("caller-owned-stream-testdata" + System.lineSeparator()));
    }

    @Test
    void schemaValidButWrongAdmissionFailsTheComparison() throws Exception {
        var results = run(suite((id, reply) -> id.equals("paid-event-testdata")
                ? reply.replace("false", "true") : reply));
        var wrong = result(results, "paid-event-testdata");
        assertTrue(wrong.accepted());
        assertEquals(0, wrong.findings());
        assertFalse(wrong.matchesExpected());
        assertFalse(wrong.passed());
        assertEquals(4, results.stream().filter(AnnouncementScenarioSuite.ScenarioResult::passed).count());
    }

    @Test
    void repairedMissingNullsFailEvenWhenNormalizedOutputMatches() throws Exception {
        var results = run(suite((id, reply) -> id.equals("missing-details-testdata")
                ? "{\"title\":\"Klub pomysłów\"}" : reply));
        var repaired = result(results, "missing-details-testdata");
        assertTrue(repaired.accepted());
        assertTrue(repaired.matchesExpected());
        assertEquals(4, repaired.findings());
        assertFalse(repaired.passed());
    }

    @Test
    void rejectedResponseDoesNotPreventLaterScenariosFromRunning() throws Exception {
        var results = run(suite((id, reply) -> id.equals("online-event-testdata") ? "[]" : reply));
        var rejected = result(results, "online-event-testdata");
        assertFalse(rejected.accepted());
        assertNull(rejected.actual());
        assertNull(rejected.error());
        assertTrue(rejected.findings() > 0);
        assertFalse(rejected.passed());
        assertTrue(result(results, "missing-price-testdata").passed());
        assertEquals(5, results.size());
    }

    @Test
    void providerFailureIsRecordedSeparatelyAndRemainingScenariosStillRun() throws Exception {
        var bytes = new ByteArrayOutputStream();
        List<AnnouncementScenarioSuite.ScenarioResult> results;
        try (var output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            results = suite((id, reply) -> {
                if (id.equals("paid-event-testdata")) throw new IllegalStateException("provider-body-testdata");
                return reply;
            }).run(output);
        }
        var failed = result(results, "paid-event-testdata");
        assertEquals("IllegalStateException", failed.error());
        assertFalse(failed.accepted());
        assertFalse(failed.passed());
        assertNull(failed.actual());
        assertNull(failed.diagnostics(), "Transport/provider exceptions must not fabricate response metadata");
        assertTrue(result(results, "missing-price-testdata").passed());
        assertEquals(4, results.stream().filter(AnnouncementScenarioSuite.ScenarioResult::passed).count());
        assertFalse(bytes.toString(StandardCharsets.UTF_8).contains("provider-body-testdata"));
    }

    @Test
    void oneCaseDiagnosticsUseTheSameEvaluationAndRejectUnknownResourcePaths() throws Exception {
        var runner = suite((id, reply) -> {
            assertEquals("online-event-testdata", id, "Only the selected case may call the model");
            return reply;
        });
        try (var output = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)) {
            var results = runner.runCase("online-event-testdata", output);
            assertEquals(1, results.size());
            assertTrue(results.get(0).passed());
            assertThrows(IllegalArgumentException.class, () -> runner.runCase("../unknown-testdata", output));
        }
    }

    @Test
    void truncatedGenerationRetainsFinishReasonInFailureEvidence() throws Exception {
        String reply = AnnouncementPilotTest.resource("responses/public-event-testdata.json");
        ChatModel model = prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage(reply),
                ChatGenerationMetadata.builder().finishReason("length").build())));
        var results = run(AnnouncementScenarioSuite.builder().model(model).build());
        assertTrue(results.stream().noneMatch(AnnouncementScenarioSuite.ScenarioResult::passed));
        var failed = results.get(0);
        var metadata = assertInstanceOf(ModelReplyDiagnostics.class, failed.diagnostics());
        assertEquals("length", metadata.finishReason());
        assertEquals(reply.length(), metadata.outputCharacters());
        assertNull(metadata.completionTokens(), "Missing usage is unknown, not zero");
        assertFalse(failed.accepted());
        assertEquals("IllegalStateException", failed.error());
    }

    private static AnnouncementScenarioSuite suite(BiFunction<String, String, String> response) throws Exception {
        var documents = new LinkedHashMap<String, String>();
        var replies = new LinkedHashMap<String, String>();
        for (var id : AnnouncementScenarioSuite.CASES) {
            documents.put(id, AnnouncementPilotTest.resource("documents/" + id + ".txt"));
            replies.put(id, AnnouncementPilotTest.resource("responses/" + id + ".json"));
        }
        ChatModel model = prompt -> {
            String user = prompt.getInstructions().get(1).getText();
            if (user == null) throw new AssertionError("Missing user message");
            // Rendering may omit a terminal newline; identify the complete document independently of line endings.
            var id = documents.entrySet().stream().filter(entry -> user.replace("\r\n", "\n")
                            .contains(entry.getValue().replace("\r\n", "\n").strip()))
                    .map(java.util.Map.Entry::getKey).findFirst().orElseThrow();
            var reply = replies.get(id);
            if (reply == null) throw new AssertionError("Missing expected reply");
            return SyntheticChatModel.builder().response(response.apply(id, reply)).build().call(prompt);
        };
        return AnnouncementScenarioSuite.builder().model(model).build();
    }

    private static List<AnnouncementScenarioSuite.ScenarioResult> run(AnnouncementScenarioSuite suite) throws Exception {
        try (var output = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)) {
            return suite.run(output);
        }
    }

    private static AnnouncementScenarioSuite.ScenarioResult result(List<AnnouncementScenarioSuite.ScenarioResult> results, String id) {
        return results.stream().filter(result -> result.id().equals(id)).findFirst().orElseThrow();
    }

    private static AnnouncementExtractionOutput actual(List<AnnouncementScenarioSuite.ScenarioResult> results, String id) {
        return assertInstanceOf(AnnouncementExtractionOutput.class, result(results, id).actual());
    }
}
