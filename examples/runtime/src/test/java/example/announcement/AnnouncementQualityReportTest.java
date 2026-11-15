package example.announcement;

import example.announcement.generated.AnnouncementExtractionOutput;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

/** Scores real published runtime decisions using doubles; no test invokes a model backend or a container. */
class AnnouncementQualityReportTest {
    @TempDir Path directory;

    @Test
    void replayExercisesEveryCaseAndCannotBeMistakenForMeasuredModelQuality() throws Exception {
        var corpus = AnnouncementRegressionCorpus.load();
        var results = run(corpus, corpus.replayModel());
        var report = AnnouncementQualityReport.create(corpus, replay(), results);
        assertEquals(25, report.summary().passedCases());
        assertEquals(125, report.summary().matchingFields());
        assertEquals(25, report.summary().schemaAcceptedCases());
        assertEquals(0, report.usage().completionTokens().reportedCases());
        assertNull(report.usage().completionTokens().total());
        assertNull(report.context().settings());
        assertEquals(corpus.sha256(), report.corpusSha256());
        assertTrue(report.cases().stream().allMatch(item -> item.outcome() == AnnouncementQualityReport.Outcome.PASSED));
        AnnouncementQualityReportWriter.write(directory, report);
        var json = JsonMapper.builder().build().readTree(Files.readString(directory.resolve("quality-report.json")));
        assertNotNull(json);
        assertEquals("SYNTHETIC_REPLAY", json.path("context").path("mode").asString());
        assertTrue(json.path("usage").path("totalTokens").path("total").isNull());
        assertEquals(25, json.path("cases").size());
        var markdown = Files.readString(directory.resolve("quality-report.md"));
        assertTrue(markdown.contains("they do not measure model quality"));
        assertTrue(markdown.contains("25/25"));
        assertTrue(markdown.contains("Unknowns preserved"));
        assertTrue(markdown.contains("Warsztat {mapy}"));
        assertThrows(UnsupportedOperationException.class, report.cases()::clear);
    }

    @Test
    void separatesWrongFactsRepairsRejectionsTruncationAndFailuresWithoutAwardingNullCredit() throws Exception {
        var corpus = AnnouncementRegressionCorpus.load();
        var calls = new AtomicInteger();
        var model = model(corpus, calls, item -> switch (item.id()) {
                case "public-event-testdata" -> response(item.expectedJson().replace("Wieczór z Javą", "Wrong but schema-valid"), "STOP");
                case "online-event-testdata" -> response(without(item.expectedJson(), "format"), "STOP");
                case "paid-event-testdata" -> response("[]", "STOP");
                case "missing-details-testdata" -> response(item.expectedJson(), "LENGTH");
                case "missing-price-testdata" -> throw new IllegalStateException("provider-secret-body-testdata");
                case "hybrid-event-testdata" -> response(" ", "STOP");
                case "ambiguous-date-testdata" -> response(without(item.expectedJson(), "date"), "STOP");
                default -> response(item.expectedJson(), "STOP");
        });
        var report = AnnouncementQualityReport.create(corpus, local(), run(corpus, model));
        assertEquals(25, calls.get(), "No retries and no early exit after failures");
        assertEquals(18, report.summary().passedCases());
        assertEquals(21, report.summary().schemaAcceptedCases());
        assertEquals(103, report.summary().matchingFields());
        assertEquals(125, report.summary().totalFields(), "Unavailable fields stay in the denominator");
        assertEquals(2, report.summary().outcomes().get(AnnouncementQualityReport.Outcome.REPAIRED));
        for (var outcome : List.of(AnnouncementQualityReport.Outcome.FACT_MISMATCH, AnnouncementQualityReport.Outcome.REJECTED,
                AnnouncementQualityReport.Outcome.TRUNCATED, AnnouncementQualityReport.Outcome.PROVIDER_ERROR,
                AnnouncementQualityReport.Outcome.RESPONSE_ERROR)) assertEquals(1, report.summary().outcomes().get(outcome));
        var truncated = item(report, "missing-details-testdata");
        assertNull(truncated.schemaAccepted(), "Truncation is rejected before schema reading");
        assertEquals("LENGTH", assertInstanceOf(ModelReplyDiagnostics.class, truncated.diagnostics()).finishReason());
        assertTrue(truncated.fields().stream().allMatch(field -> field.comparison() == AnnouncementQualityReport.Comparison.UNAVAILABLE));
        var repairedNull = item(report, "ambiguous-date-testdata");
        assertTrue(Boolean.TRUE.equals(repairedNull.schemaAccepted()));
        assertTrue(repairedNull.fields().stream().allMatch(field -> field.comparison() == AnnouncementQualityReport.Comparison.MATCH));
        assertEquals(AnnouncementQualityReport.Outcome.REPAIRED, repairedNull.outcome());
        var findings = repairedNull.validationFindings();
        assertNotNull(findings);
        assertTrue(findings.stream().anyMatch(finding -> finding.pointer().equals("/date") && finding.rule().equals("required")));
        assertNull(item(report, "missing-price-testdata").findings());
        assertTrue(report.cases().get(24).outcome() == AnnouncementQualityReport.Outcome.PASSED);
        AnnouncementQualityReportWriter.write(directory, report);
        var json = Files.readString(directory.resolve("quality-report.json"));
        assertFalse(json.contains("provider-secret-body-testdata"));
        assertTrue(json.contains("\"validationFindings\""));
        assertTrue(json.contains("sha256:" + "a".repeat(64)));
        assertTrue(json.contains("4096"));
        assertTrue(json.contains("LOCAL_MODEL"));
    }

    @Test
    void tokenTotalsExposePartialCoverageAndDoNotTurnMissingCountersIntoZero() throws Exception {
        var corpus = AnnouncementRegressionCorpus.load(AnnouncementRegressionCorpusTest.manifest("public-event-testdata", "online-event-testdata"),
                path -> new org.springframework.core.io.ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8));
        var original = run(corpus, corpus.replayModel());
        var first = original.get(0);
        var diagnostics = ModelReplyDiagnostics.builder().finishReason("STOP").promptTokens(100).completionTokens(40)
                .totalTokens(140).reasoningTokens(12L).build();
        var withUsage = AnnouncementScenarioSuite.ScenarioResult.builder().id(first.id()).expected(first.expected())
                .actual(first.actual()).accepted(true).matchesExpected(true).validationFindings(first.validationFindings())
                .elapsedMillis(120).diagnostics(diagnostics).build();
        var report = AnnouncementQualityReport.create(corpus, local(), List.of(withUsage, original.get(1)));
        assertEquals(1, report.usage().promptTokens().reportedCases());
        assertEquals(100L, report.usage().promptTokens().total());
        assertEquals(40L, report.usage().completionTokens().total());
        assertEquals(140L, report.usage().totalTokens().total());
        assertEquals(12L, report.usage().reasoningTokens().total());
    }

    @Test
    void incompleteReorderedOrChangedOraclesCannotProduceAReport() throws Exception {
        var corpus = AnnouncementRegressionCorpus.load();
        var original = run(corpus, corpus.replayModel());
        assertThrows(IllegalArgumentException.class, () -> AnnouncementQualityReport.create(corpus, replay(), original.subList(1, original.size())));
        var swapped = new ArrayList<>(original);
        java.util.Collections.swap(swapped, 0, 1);
        assertThrows(IllegalArgumentException.class, () -> AnnouncementQualityReport.create(corpus, replay(), swapped));
        var duplicated = new ArrayList<>(original);
        duplicated.set(1, original.get(0));
        assertThrows(IllegalArgumentException.class, () -> AnnouncementQualityReport.create(corpus, replay(), duplicated));
        var changed = new ArrayList<>(original);
        var first = original.get(0);
        changed.set(0, AnnouncementScenarioSuite.ScenarioResult.builder().id(first.id())
                .expected(new AnnouncementExtractionOutput(null, null, null, null, null)).build());
        assertThrows(IllegalArgumentException.class, () -> AnnouncementQualityReport.create(corpus, replay(), changed));
        assertThrows(IllegalArgumentException.class, () -> AnnouncementQualityReport.RunContext.builder()
                .mode(AnnouncementQualityReport.Mode.LOCAL_MODEL).build());
    }

    @ParameterizedTest
    @ValueSource(strings = {"accepted", "matches", "findings-count", "missing-findings", "negative-findings", "negative-time", "failed-output", "truncated-output"})
    void contradictoryEvidenceCannotProduceMisleadingScores(String mutation) throws Exception {
        var corpus = AnnouncementRegressionCorpus.load();
        var results = new ArrayList<>(run(corpus, corpus.replayModel()));
        var first = results.get(0);
        var changed = AnnouncementScenarioSuite.ScenarioResult.builder().id(first.id()).expected(first.expected())
                .actual(first.actual()).accepted(first.accepted()).matchesExpected(first.matchesExpected())
                .findings(first.findings()).validationFindings(first.validationFindings())
                .elapsedMillis(first.elapsedMillis()).diagnostics(first.diagnostics());
        switch (mutation) {
            case "accepted" -> changed.accepted(false);
            case "matches" -> changed.matchesExpected(false);
            case "findings-count" -> changed.findings(1);
            case "missing-findings" -> changed.validationFindings(null);
            case "negative-findings" -> changed.findings(-1);
            case "negative-time" -> changed.elapsedMillis(-1);
            case "failed-output" -> changed.error("ProviderFailureTestdata");
            case "truncated-output" -> changed.diagnostics(ModelReplyDiagnostics.builder().finishReason("LENGTH").build());
            default -> throw new AssertionError("Unknown evidence mutation");
        }
        results.set(0, changed.build());
        assertThrows(IllegalArgumentException.class, () -> AnnouncementQualityReport.create(corpus, local(), results));
    }

    @Test
    void reportedZeroAndLargeTokenSumsRemainDistinctFromUnknownCounters() throws Exception {
        var corpus = AnnouncementRegressionCorpus.load(AnnouncementRegressionCorpusTest.manifest("public-event-testdata", "online-event-testdata"),
                path -> new org.springframework.core.io.ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8));
        var original = run(corpus, corpus.replayModel());
        var metadata = ModelReplyDiagnostics.builder().promptTokens(Integer.MAX_VALUE).completionTokens(0).build();
        var results = original.stream().map(item -> AnnouncementScenarioSuite.ScenarioResult.builder().id(item.id())
                .expected(item.expected()).actual(item.actual()).accepted(item.accepted()).matchesExpected(item.matchesExpected())
                .findings(item.findings()).validationFindings(item.validationFindings()).diagnostics(metadata).build()).toList();
        var report = AnnouncementQualityReport.create(corpus, local(), results);
        assertEquals(2, report.usage().promptTokens().reportedCases());
        assertEquals(2L * Integer.MAX_VALUE, report.usage().promptTokens().total(), "Aggregate in long, not int");
        assertEquals(2, report.usage().completionTokens().reportedCases());
        assertEquals(0L, report.usage().completionTokens().total(), "An explicitly reported zero remains a known value");
        assertEquals(0, report.usage().totalTokens().reportedCases());
        assertNull(report.usage().totalTokens().total(), "Do not synthesize absent provider counters from other sums");
    }

    @Test
    void reportRetainsImmutableSnapshotsOfCallerCollectionsAndFindings() throws Exception {
        var corpus = AnnouncementRegressionCorpus.load();
        var results = new ArrayList<>(run(corpus, SyntheticChatModel.builder().response("{}").build()));
        var report = AnnouncementQualityReport.create(corpus, replay(), results);
        results.clear();
        assertEquals(25, report.cases().size());
        assertThrows(UnsupportedOperationException.class, report.fields()::clear);
        assertThrows(UnsupportedOperationException.class, report.summary().outcomes()::clear);
        var first = report.cases().get(0);
        assertThrows(UnsupportedOperationException.class, first.fields()::clear);
        assertThrows(UnsupportedOperationException.class, first.tags()::clear);
        var findings = first.validationFindings();
        assertNotNull(findings);
        assertFalse(findings.isEmpty());
        assertThrows(UnsupportedOperationException.class, findings::clear);
        for (var field : report.fields()) {
            assertEquals(field.total(), field.expectedKnown() + field.expectedUnknown());
            assertEquals(field.matched(), field.matchedKnown() + field.matchedUnknown());
        }
    }

    @Test
    void provenanceCannotClaimDifferentModelOptionsOrMixReplayWithProviderSettings() {
        var settings = local().settings();
        assertNotNull(settings);
        assertThrows(IllegalArgumentException.class, () -> AnnouncementQualityReport.RunContext.builder()
                .mode(AnnouncementQualityReport.Mode.SYNTHETIC_REPLAY).settings(settings).build());
        for (double temperature : new double[]{0.5, -0.5, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> AnnouncementQualityReport.RunContext.builder()
                    .mode(AnnouncementQualityReport.Mode.LOCAL_MODEL).settings(settings).temperature(temperature).build());
        }
        for (int retries : new int[]{-1, 1}) {
            assertThrows(IllegalArgumentException.class, () -> AnnouncementQualityReport.RunContext.builder()
                    .mode(AnnouncementQualityReport.Mode.LOCAL_MODEL).settings(settings).maxRetries(retries).build());
        }
    }

    @Test
    void failedRunWritesBothReportsBeforeFailingAndKeepsCallerStreamOpen() throws Exception {
        var corpus = AnnouncementRegressionCorpus.load();
        var bytes = new ByteArrayOutputStream();
        try (var output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            assertThrows(IllegalStateException.class, () -> PilotApplication.regression(corpus,
                    SyntheticChatModel.builder().response("[]").build(), replay(), directory, output));
            output.println("still-open-testdata");
            assertFalse(output.checkError());
        }
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("cases=25; passed=0"));
        var json = JsonMapper.builder().build().readTree(Files.readString(directory.resolve("quality-report.json")));
        assertNotNull(json);
        assertEquals(25, json.path("summary").path("outcomes").path("REJECTED").asInt());
        assertTrue(Files.isRegularFile(directory.resolve("quality-report.md")));
        var file = directory.resolve("not-a-directory-testdata");
        Files.writeString(file, "existing file");
        assertThrows(java.io.IOException.class, () -> AnnouncementQualityReportWriter.write(file,
                AnnouncementQualityReport.create(corpus, replay(), run(corpus, corpus.replayModel()))));
    }

    @Test
    void markdownTreatsProviderTextAsDataRatherThanHtmlLinksOrTableRows() throws Exception {
        var corpus = AnnouncementRegressionCorpus.load();
        String unsafe = "<script>alert(1)</script>|[click](https://example.invalid)\r\n**bold**`\u202e\u2028\u2029";
        var report = AnnouncementQualityReport.create(corpus, local(), run(corpus, model(corpus, new AtomicInteger(), item -> {
            var json = assertInstanceOf(ObjectNode.class, JsonMapper.builder().build().readTree(item.expectedJson()));
            json.put("title", unsafe);
            return response(json.toString(), "STOP");
        })));
        String markdown = AnnouncementQualityReportWriter.markdown(report);
        assertFalse(markdown.contains("<script>"));
        assertFalse(markdown.contains("[click]("));
        assertFalse(markdown.contains("**bold**"));
        assertFalse(markdown.contains("\u202e"));
        assertFalse(markdown.contains("\u2028"));
        assertFalse(markdown.contains("\u2029"));
        assertTrue(markdown.contains("&lt;script&gt;"));
        assertTrue(markdown.contains("&#124;"));
        assertTrue(markdown.contains("[control]"));
    }

    @Test
    void explicitReplayCliIgnoresPoisonedModelSettingsAndProducesUtf8Reports() throws Exception {
        try (var output = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)) {
            PilotApplication.run(new String[]{"--regression-report=" + directory}, Map.of("PROMPTJIG_MODEL", "mutable-invalid-testdata"), output);
            assertThrows(IllegalArgumentException.class, () -> PilotApplication.run(new String[]{"--regression-report="}, Map.of(), output));
            assertThrows(IllegalArgumentException.class, () -> PilotApplication.run(
                    new String[]{"--local-regression-report=" + directory.resolve("local")}, Map.of(), output));
        }
        assertTrue(Files.readString(directory.resolve("quality-report.json")).contains("Wieczór z Javą"));
    }

    private static AnnouncementQualityReport.RunContext replay() {
        return AnnouncementQualityReport.RunContext.builder().mode(AnnouncementQualityReport.Mode.SYNTHETIC_REPLAY).build();
    }

    private static AnnouncementQualityReport.RunContext local() {
        return AnnouncementQualityReport.RunContext.builder().mode(AnnouncementQualityReport.Mode.LOCAL_MODEL)
                .settings(LocalModelSettings.builder().baseUrl("http://127.0.0.1:12434/engines/v1").model("sha256:" + "a".repeat(64))
                        .maxTokens(4096).timeout(Duration.ofSeconds(600)).build()).build();
    }

    private static String without(String json, String field) {
        var node = assertInstanceOf(ObjectNode.class, JsonMapper.builder().build().readTree(json));
        node.remove(field);
        return node.toString();
    }

    private static ChatResponse response(String json, String finishReason) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(json), ChatGenerationMetadata.builder().finishReason(finishReason).build())));
    }

    private static ChatModel model(AnnouncementRegressionCorpus corpus, AtomicInteger calls,
            Function<AnnouncementRegressionCorpus.Case, ChatResponse> reply) {
        return prompt -> {
            var item = corpus.cases().get(calls.getAndIncrement());
            var text = prompt.getInstructions().get(1).getText();
            assertNotNull(text);
            assertTrue(text.replace("\r\n", "\n").contains(item.document().strip()), "Render the source, not its expected answer");
            return reply.apply(item);
        };
    }

    private static List<AnnouncementScenarioSuite.ScenarioResult> run(AnnouncementRegressionCorpus corpus, ChatModel model) throws Exception {
        try (var output = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)) {
            return AnnouncementScenarioSuite.builder().model(model).build().runRegression(corpus, output);
        }
    }

    private static AnnouncementQualityReport.CaseResult item(AnnouncementQualityReport report, String id) {
        return report.cases().stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
    }
}
