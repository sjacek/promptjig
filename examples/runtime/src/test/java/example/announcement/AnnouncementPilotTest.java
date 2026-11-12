package example.announcement;

import example.announcement.generated.AnnouncementExtractionOutput;
import io.github.sjacek.promptjig.springai.PromptReadResult;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.ClassPathResource;
import static org.junit.jupiter.api.Assertions.*;

/** Checks the rendered/typed boundary with controllable Spring AI models, including provider failures. */
class AnnouncementPilotTest {
    @Test
    void addingTheSchemaKeepsCallerOptionsUnchangedAndSuccessiveInputsIndependent() throws Exception {
        var defaults = OpenAiChatOptions.builder().model(LocalModelSettingsTest.DIGEST).temperature(0.25)
                .maxTokens(64).timeout(java.time.Duration.ofSeconds(7)).maxRetries(0).build();
        var captured = new java.util.ArrayList<Prompt>();
        var delegate = SyntheticChatModel.builder().response(resource("responses/public-event-testdata.json")).build();
        ChatModel recording = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                captured.add(prompt);
                return delegate.call(prompt);
            }

            @Override
            public OpenAiChatOptions getOptions() { return defaults; }
        };
        var pilot = AnnouncementPilot.builder().model(recording).build();
        assertTrue(pilot.extract("first-announcement-testdata {example}").accepted());
        assertTrue(pilot.extract("second-announcement-testdata").accepted());
        assertEquals(2, captured.size());
        for (var prompt : captured) {
            var options = assertInstanceOf(OpenAiChatOptions.class, prompt.getOptions());
            assertEquals(LocalModelSettingsTest.DIGEST, options.getModel());
            assertEquals(0.25, options.getTemperature());
            assertEquals(64, options.getMaxTokens());
            assertEquals(java.time.Duration.ofSeconds(7), options.getTimeout());
            assertEquals(0, options.getMaxRetries());
            assertNotNull(options.getResponseFormat());
        }
        assertNull(defaults.getResponseFormat(), "Adding the schema must not mutate caller-owned defaults");
        assertEquals(LocalModelSettingsTest.DIGEST, defaults.getModel());
        var first = captured.get(0).getInstructions().get(1).getText();
        var second = captured.get(1).getInstructions().get(1).getText();
        if (first == null || second == null) throw new AssertionError("Missing rendered document");
        assertTrue(first.contains("first-announcement-testdata {example}"));
        assertFalse(first.contains("second-announcement-testdata"));
        assertTrue(second.contains("second-announcement-testdata"));
        assertFalse(second.contains("first-announcement-testdata"));
    }

    @Test
    void providerFailurePropagatesWithoutReturningAnAcceptedOrRepairedResponse() {
        var calls = new AtomicInteger();
        ChatModel unavailable = prompt -> {
            calls.incrementAndGet();
            throw new IllegalStateException("unavailable-backend-testdata");
        };
        var pilot = AnnouncementPilot.builder().model(unavailable).build();
        assertThrows(RuntimeException.class, () -> pilot.extract("synthetic announcement-testdata"));
        assertEquals(1, calls.get());
    }

    @Test
    void rendersClasspathTemplatesCallsSpringAiAndReadsGeneratedTypes() throws Exception {
        var delegate = SyntheticChatModel.builder().response(resource("responses/public-event-testdata.json")).build();
        var calls = new AtomicInteger();
        var captured = new AtomicReference<Prompt>();
        ChatModel recording = prompt -> {
            captured.set(prompt);
            calls.incrementAndGet();
            return delegate.call(prompt);
        };
        var result = AnnouncementPilot.builder().model(recording).build()
                .extract(resource("documents/public-event-testdata.txt"));

        assertEquals(1, calls.get());
        var output = accepted(result);
        assertEquals("Wieczór z Javą", output.title());
        assertEquals(LocalDate.of(2026, 12, 10), output.date());
        assertEquals("Przykładowy Dom Kultury", output.location());
        assertEquals(AnnouncementExtractionOutput.Format.IN_PERSON, output.format());
        assertEquals(Boolean.TRUE, output.free());
        assertTrue(result.violations().isEmpty());
        var prompt = captured.get();
        if (prompt == null) throw new AssertionError("No prompt captured");
        assertEquals(2, prompt.getInstructions().size());
        var system = assertInstanceOf(SystemMessage.class, prompt.getInstructions().get(0)).getText();
        var user = assertInstanceOf(UserMessage.class, prompt.getInstructions().get(1)).getText();
        if (system == null || user == null) throw new AssertionError("Missing rendered message text");
        assertTrue(system.contains("IN_PERSON, ONLINE, HYBRID"));
        assertTrue(system.contains("date, format, free, location, title"));
        assertTrue(user.contains("Wieczór z Javą"));
        assertTrue(user.contains("{example}"), "Input braces must remain literal source data");
    }

    @Test
    void repairsInvalidNullableFieldsAndReportsFindingsWithoutOriginalValues() {
        String raw = """
                {"date":"2026-02-30","format":"UNKNOWN_TESTDATA","free":"yes",
                 "unrecognised-testdata":"discarded-testdata"}
                """;
        var result = pilot(raw).extract("synthetic public announcement-testdata");
        var output = accepted(result);
        assertNull(output.date());
        assertNull(output.format());
        assertNull(output.free());
        assertNull(output.title());
        assertNull(output.location());
        assertFalse(result.violations().isEmpty());
        assertTrue(result.violations().stream().anyMatch(finding -> finding.pointer().equals("/date")));
        assertTrue(result.violations().stream().anyMatch(finding -> finding.pointer().equals("/format") && finding.rule().equals("enum")));
        assertFalse(result.violations().toString().contains("UNKNOWN_TESTDATA"));
        assertFalse(result.violations().toString().contains("discarded-testdata"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "[]", "null", "{\"title\":\"one\"} {\"title\":\"two\"}", "{\"title\":\"one\",\"title\":\"two\"}"})
    void rejectsMalformedOrStructurallyInvalidResponses(String raw) {
        var result = pilot(raw).extract("synthetic public announcement-testdata");
        assertFalse(result.accepted());
        assertNull(result.value());
        assertFalse(result.violations().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " \n\t"})
    void refusesEmptyAssistantText(String raw) {
        assertThrows(IllegalStateException.class, () -> pilot(raw).extract("synthetic announcement-testdata"));
    }

    @Test
    void refusesAResponseWithoutGenerations() {
        ChatModel empty = prompt -> new ChatResponse(List.of());
        var pilot = AnnouncementPilot.builder().model(empty).build();
        assertThrows(IllegalStateException.class, () -> pilot.extract("synthetic announcement-testdata"));
    }

    private static AnnouncementPilot pilot(String response) {
        return AnnouncementPilot.builder().model(SyntheticChatModel.builder().response(response).build()).build();
    }

    private static AnnouncementExtractionOutput accepted(PromptReadResult<AnnouncementExtractionOutput> result) {
        assertTrue(result.accepted(), result.violations().toString());
        var value = result.value();
        if (value == null) throw new AssertionError("Missing accepted output");
        return value;
    }

    static String resource(String path) throws Exception {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }
}
