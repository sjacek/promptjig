package example.announcement;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

/** Reviews the packaged oracle boundary, including corrupt fixtures and reproducible cross-platform identities. */
class AnnouncementRegressionCorpusTest {
    @Test
    void bundledCorpusHasReviewedAnswersForAllEdgeCasesAndIsImmutable() throws Exception {
        var corpus = AnnouncementRegressionCorpus.load();
        assertEquals(25, corpus.cases().size());
        assertEquals(25, corpus.cases().stream().map(AnnouncementRegressionCorpus.Case::id).distinct().count());
        assertEquals(1, corpus.version());
        assertTrue(corpus.sha256().matches("[0-9a-f]{64}"));
        assertThrows(UnsupportedOperationException.class, corpus.cases()::clear);
        assertThrows(UnsupportedOperationException.class, () -> corpus.cases().get(0).tags().clear());
        for (String id : List.of("ambiguous-date-testdata", "no-year-testdata", "multi-day-testdata"))
            assertNull(item(corpus, id).expected().date(), "No unique full date in " + id);
        assertNull(item(corpus, "conflicting-place-testdata").expected().location());
        assertNull(item(corpus, "mixed-admission-testdata").expected().free());
        assertNull(item(corpus, "registration-not-admission-testdata").expected().free());
        assertNull(item(corpus, "ambiguous-format-testdata").expected().format());
        assertNull(item(corpus, "missing-title-testdata").expected().title());
        assertEquals("2028-02-29", String.valueOf(item(corpus, "leap-day-testdata").expected().date()));
        assertEquals("Warsztat {mapy}", item(corpus, "json-braces-testdata").expected().title());
    }

    @Test
    void contentIdentityIsStableAcrossLineEndingsButChangesWithSourceAndOracle() throws Exception {
        String manifest = manifest("public-event-testdata");
        var original = AnnouncementRegressionCorpus.load(manifest, AnnouncementRegressionCorpusTest::resource);
        var windows = AnnouncementRegressionCorpus.load(manifest.replace("\n", "\r\n"),
                path -> resource(path).replace("\r\n", "\n").replace("\n", "\r\n"));
        assertEquals(original.sha256(), windows.sha256());
        var source = AnnouncementRegressionCorpus.load(manifest,
                path -> path.endsWith(".txt") ? resource(path) + "Dodatkowy fikcyjny fakt.\n" : resource(path));
        assertNotEquals(original.sha256(), source.sha256());
        var oracle = AnnouncementRegressionCorpus.load(manifest,
                path -> path.endsWith(".json") ? resource(path).replace("Wieczór z Javą", "Inny fikcyjny tytuł") : resource(path));
        assertNotEquals(original.sha256(), oracle.sha256());
        assertNotEquals(original.cases().get(0).expectedSha256(), oracle.cases().get(0).expectedSha256());
    }

    @ParameterizedTest
    @ValueSource(strings = {"../outside-testdata", "https://example.org/data", "not-a-dataset", "UPPER-testdata"})
    void invalidIdentifiersFailBeforeReadingAnyResource(String id) {
        assertThrows(IllegalArgumentException.class, () -> AnnouncementRegressionCorpus.load(manifest(id), path -> {
            throw new AssertionError("Invalid IDs cannot select a resource");
        }));
    }

    @Test
    void duplicateEmptyAndUnsupportedCorpusDefinitionsAreRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> AnnouncementRegressionCorpus.load(
                manifest("public-event-testdata", "public-event-testdata"), AnnouncementRegressionCorpusTest::resource));
        assertThrows(IllegalArgumentException.class, () -> AnnouncementRegressionCorpus.load(manifest(), AnnouncementRegressionCorpusTest::resource));
        assertThrows(IllegalArgumentException.class, () -> AnnouncementRegressionCorpus.load(
                manifest("public-event-testdata").replace("\"version\":1", "\"version\":0"), AnnouncementRegressionCorpusTest::resource));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "[]", "null", "not-json", "{\"title\":\"Oracle without required nulls\"}"})
    void malformedRejectedAndRepairableOraclesCannotEnterAnEvaluation(String oracle) {
        assertThrows(RuntimeException.class, () -> AnnouncementRegressionCorpus.load(manifest("public-event-testdata"),
                path -> path.endsWith(".json") ? oracle : resource(path)));
    }

    @Test
    void missingAndBlankDocumentsAreInfrastructureFailuresRatherThanModelScores() {
        assertThrows(IOException.class, () -> AnnouncementRegressionCorpus.load(manifest("public-event-testdata"), path -> {
            throw new IOException("Missing fictional fixture");
        }));
        assertThrows(IllegalArgumentException.class, () -> AnnouncementRegressionCorpus.load(manifest("public-event-testdata"),
                path -> path.endsWith(".txt") ? "  \n" : resource(path)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"blank-note", "empty-tags", "invalid-tag", "missing-id", "null-cases", "null-entry"})
    void incompleteReviewMetadataCannotSelectResources(String invalid) {
        var mapper = JsonMapper.builder().build();
        var manifest = assertInstanceOf(ObjectNode.class, mapper.readTree(manifest("public-event-testdata")));
        var entry = assertInstanceOf(ObjectNode.class, manifest.path("cases").get(0));
        switch (invalid) {
            case "blank-note" -> entry.put("reviewNote", " \n");
            case "empty-tags" -> entry.putArray("tags");
            case "invalid-tag" -> entry.putArray("tags").add("not a tag");
            case "missing-id" -> entry.remove("id");
            case "null-cases" -> manifest.putNull("cases");
            case "null-entry" -> manifest.putArray("cases").addNull();
            default -> throw new AssertionError("Unknown metadata mutation");
        }
        assertThrows(RuntimeException.class, () -> AnnouncementRegressionCorpus.load(manifest.toString(), path -> {
            throw new AssertionError("An unreviewed entry cannot load a fixture");
        }));
    }

    @Test
    void replayFailsWhenRenderedSourceHasNoUniqueOracle() throws Exception {
        var corpus = AnnouncementRegressionCorpus.load();
        var pilot = AnnouncementPilot.builder().model(corpus.replayModel()).build();
        assertThrows(IllegalStateException.class, () -> pilot.extract("An unrelated fictional announcement-testdata"));

        var original = corpus.cases().get(0);
        var duplicate = AnnouncementRegressionCorpus.Case.builder().id("duplicate-event-testdata")
                .tags(original.tags()).reviewNote("Deliberately ambiguous replay fixture.")
                .document(original.document()).expectedJson(original.expectedJson()).expected(original.expected())
                .documentSha256(original.documentSha256()).expectedSha256(original.expectedSha256()).build();
        var ambiguous = AnnouncementRegressionCorpus.builder().version(1).sha256(corpus.sha256())
                .cases(List.of(original, duplicate)).build();
        var ambiguousPilot = AnnouncementPilot.builder().model(ambiguous.replayModel()).build();
        assertThrows(IllegalStateException.class, () -> ambiguousPilot.extract(original.document()));
    }

    static String manifest(String... ids) {
        return JsonMapper.builder().build().writeValueAsString(Map.of("version", 1, "cases", java.util.Arrays.stream(ids)
                .map(id -> Map.of("id", id, "tags", List.of("reviewed"), "reviewNote", "Reviewed fictional test case.")).toList()));
    }

    private static String resource(String path) throws IOException {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }

    private static AnnouncementRegressionCorpus.Case item(AnnouncementRegressionCorpus corpus, String id) {
        return corpus.cases().stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
    }
}
