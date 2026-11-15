package example.announcement;

import example.announcement.generated.AnnouncementExtractionOutput;
import example.announcement.generated.AnnouncementExtractionPromptContract;
import io.github.sjacek.promptjig.core.ResponseViolation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import lombok.Builder;
import lombok.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Machine-readable evaluation of the reviewed corpus, not an estimate of general model accuracy.
 * Matching normalized fields, reader acceptance and repair-free case passes remain separate metrics.
 * Rejected/failed responses contribute unavailable fields rather than invented matching nulls.
 * @param reportVersion JSON evidence format version, independent of the dataset revision
 * @param createdAt UTC timestamp when the completed report was assembled
 * @param corpusVersion reviewed dataset revision
 * @param corpusSha256 identity of manifest, normalized sources and reviewed answers
 * @param contractVersion generated prompt contract version reference
 * @param contractFingerprint generated contract content fingerprint
 * @param context explicit execution mode and fixed request settings
 * @param summary case counts, normalized field counts and extraction time
 * @param fields scores in generated output field order, including unavailable answers in denominators
 * @param usage independent provider counters with reporting coverage
 * @param cases immutable evidence in corpus execution order
 */
@Builder
public record AnnouncementQualityReport(int reportVersion, @NonNull String createdAt, int corpusVersion,
        @NonNull String corpusSha256, @NonNull String contractVersion, @NonNull String contractFingerprint,
        @NonNull RunContext context, @NonNull Summary summary, @NonNull List<FieldScore> fields,
        @NonNull Usage usage, @NonNull List<CaseResult> cases) {
    private static final List<String> FIELD_NAMES = List.of("title", "date", "location", "format", "free");

    public AnnouncementQualityReport {
        fields = List.copyOf(fields);
        cases = List.copyOf(cases);
    }

    /**
     * Builds a complete report, including unsuccessful calls. Partial/duplicate/reordered result lists are
     * rejected so a missing scenario cannot silently improve scores. Elapsed times exclude fixture loading.
     * @param corpus immutable reviewed corpus
     * @param context explicit replay or local-model settings; never environment variables or credentials
     * @param results exactly one result per corpus case, in corpus order
     * @return versioned report with reproducible corpus and contract identities
     * @throws IllegalArgumentException if evidence is incomplete or contradicts its value, flags or findings
     */
    public static AnnouncementQualityReport create(@NonNull AnnouncementRegressionCorpus corpus,
            @NonNull RunContext context, @NonNull List<AnnouncementScenarioSuite.ScenarioResult> results) {
        if (results.size() != corpus.cases().size()) throw new IllegalArgumentException("Incomplete regression results");
        var cases = new ArrayList<CaseResult>();
        var outcomes = new LinkedHashMap<Outcome, Long>();
        for (var outcome : Outcome.values()) outcomes.put(outcome, 0L);
        for (int index = 0; index < results.size(); index++) {
            var result = results.get(index);
            var fixture = corpus.cases().get(index);
            if (!fixture.id().equals(result.id()) || !fixture.expected().equals(result.expected())) {
                throw new IllegalArgumentException("Result does not match reviewed corpus order/oracle");
            }
            validateResult(result);
            var comparisons = new ArrayList<FieldComparison>();
            for (var name : FIELD_NAMES) {
                Object expected = field(result.expected(), name);
                Object actual = result.actual() == null ? null : field(result.actual(), name);
                var comparison = result.actual() == null ? Comparison.UNAVAILABLE
                        : Objects.equals(expected, actual) ? Comparison.MATCH : Comparison.MISMATCH;
                comparisons.add(FieldComparison.builder().field(name).expected(expected).actual(actual).comparison(comparison).build());
            }
            var outcome = outcome(result);
            outcomes.compute(outcome, (key, count) -> count == null ? 1L : count + 1);
            cases.add(CaseResult.builder().id(fixture.id()).tags(fixture.tags()).reviewNote(fixture.reviewNote())
                    .documentSha256(fixture.documentSha256()).expectedSha256(fixture.expectedSha256())
                    .outcome(outcome).schemaAccepted(result.error() == null ? result.accepted() : null)
                    .findings(result.error() == null ? result.findings() : null).expected(result.expected()).actual(result.actual())
                    .error(result.error()).elapsedMillis(result.elapsedMillis()).diagnostics(result.diagnostics())
                    .validationFindings(result.validationFindings())
                    .fields(comparisons).build());
        }
        var scores = FIELD_NAMES.stream().map(name -> score(name, cases)).toList();
        var summary = Summary.builder().totalCases(cases.size()).passedCases(outcomes.getOrDefault(Outcome.PASSED, 0L))
                .schemaAcceptedCases(cases.stream().filter(item -> Boolean.TRUE.equals(item.schemaAccepted())).count())
                .matchingFields(scores.stream().mapToLong(FieldScore::matched).sum()).totalFields(cases.size() * FIELD_NAMES.size())
                .elapsedMillis(results.stream().mapToLong(AnnouncementScenarioSuite.ScenarioResult::elapsedMillis).sum())
                .outcomes(outcomes).build();
        return builder().reportVersion(1).createdAt(Instant.now().toString()).corpusVersion(corpus.version())
                .corpusSha256(corpus.sha256()).contractVersion(AnnouncementExtractionPromptContract.VERSION_REF)
                .contractFingerprint(AnnouncementExtractionPromptContract.FINGERPRINT).context(context).summary(summary)
                .fields(scores).usage(Usage.builder().promptTokens(counter(cases, ModelReplyDiagnostics::promptTokens))
                        .completionTokens(counter(cases, ModelReplyDiagnostics::completionTokens))
                        .totalTokens(counter(cases, ModelReplyDiagnostics::totalTokens))
                        .reasoningTokens(counter(cases, ModelReplyDiagnostics::reasoningTokens)).build()).cases(cases).build();
    }

    // Recompute invariants at the evidence boundary. Otherwise a stale matchesExpected flag could
    // label a wrong answer PASSED while the same report's field table shows a mismatch.
    private static void validateResult(AnnouncementScenarioSuite.ScenarioResult result) {
        var findings = result.validationFindings();
        boolean truncated = result.diagnostics() != null && result.diagnostics().truncated();
        if (result.elapsedMillis() < 0 || result.findings() < 0
                || result.accepted() != (result.actual() != null)
                || result.matchesExpected() != result.expected().equals(result.actual())
                || (result.error() == null) != (findings != null)
                || (findings != null && findings.size() != result.findings())
                || (truncated && result.actual() != null)
                || (result.error() != null && (result.actual() != null || result.findings() != 0 || findings != null))) {
            throw new IllegalArgumentException("Inconsistent regression result: " + result.id());
        }
    }

    private static Outcome outcome(AnnouncementScenarioSuite.ScenarioResult result) {
        // Classify in lifecycle order: truncation and call failures occur before the reader can accept JSON.
        // Repairs take precedence over fact mismatch as the primary outcome; the field table still shows both.
        if (result.diagnostics() != null && result.diagnostics().truncated()) return Outcome.TRUNCATED;
        if (result.error() != null) return result.diagnostics() == null ? Outcome.PROVIDER_ERROR : Outcome.RESPONSE_ERROR;
        if (!result.accepted()) return Outcome.REJECTED;
        // A repaired answer may match the oracle after normalization, but must never pass the case.
        if (result.findings() > 0) return Outcome.REPAIRED;
        return result.matchesExpected() ? Outcome.PASSED : Outcome.FACT_MISMATCH;
    }

    private static FieldScore score(String name, List<CaseResult> cases) {
        var values = cases.stream().flatMap(item -> item.fields().stream()).filter(item -> item.field().equals(name)).toList();
        return FieldScore.builder().field(name).total(values.size())
                .available(values.stream().filter(item -> item.comparison() != Comparison.UNAVAILABLE).count())
                .matched(values.stream().filter(item -> item.comparison() == Comparison.MATCH).count())
                .expectedKnown(values.stream().filter(item -> item.expected() != null).count())
                .matchedKnown(values.stream().filter(item -> item.expected() != null && item.comparison() == Comparison.MATCH).count())
                .expectedUnknown(values.stream().filter(item -> item.expected() == null).count())
                .matchedUnknown(values.stream().filter(item -> item.expected() == null && item.comparison() == Comparison.MATCH).count()).build();
    }

    private static TokenCounter counter(List<CaseResult> cases, Function<ModelReplyDiagnostics, ? extends Number> value) {
        var reported = cases.stream().map(CaseResult::diagnostics).filter(Objects::nonNull)
                .map(value).filter(Objects::nonNull).toList();
        return TokenCounter.builder().reportedCases(reported.size())
                .total(reported.isEmpty() ? null : reported.stream().mapToLong(Number::longValue).sum()).build();
    }

    private static @Nullable Object field(AnnouncementExtractionOutput output, String name) {
        return switch (name) {
            case "title" -> output.title();
            case "date" -> output.date();
            case "location" -> output.location();
            case "format" -> output.format();
            case "free" -> output.free();
            default -> throw new IllegalArgumentException("Unknown generated output field");
        };
    }

    /** Execution provenance; replay is never labelled as a measured model run. */
    public enum Mode { SYNTHETIC_REPLAY, LOCAL_MODEL }
    /** Mutually exclusive primary outcome; field comparisons also expose mismatches in repaired responses. */
    public enum Outcome { PASSED, FACT_MISMATCH, REPAIRED, REJECTED, TRUNCATED, RESPONSE_ERROR, PROVIDER_ERROR }
    /** UNAVAILABLE is distinct from a returned JSON null and never earns credit for missing facts. */
    public enum Comparison { MATCH, MISMATCH, UNAVAILABLE }

    /** Only validated local settings enter a real run's provenance; replay has no provider settings. */
    @Builder
    public record RunContext(@NonNull Mode mode, @Nullable LocalModelSettings settings, double temperature, int maxRetries) {
        public RunContext {
            if ((mode == Mode.LOCAL_MODEL) != (settings != null)) throw new IllegalArgumentException("Mode/settings mismatch");
            if (temperature != 0.0 || maxRetries != 0) throw new IllegalArgumentException("Pilot uses temperature 0 and zero retries");
        }
    }

    /** Counts all attempted cases and fields, including failures; elapsed time is the sum of extraction durations. */
    @Builder
    public record Summary(int totalCases, long passedCases, long schemaAcceptedCases, long matchingFields,
            int totalFields, long elapsedMillis, @NonNull Map<Outcome, Long> outcomes) {
        public Summary { outcomes = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(outcomes)); }
    }

    /** Known and unknown facts have separate denominators; unavailable responses remain in both totals. */
    @Builder
    public record FieldScore(@NonNull String field, long total, long available, long matched,
            long expectedKnown, long matchedKnown, long expectedUnknown, long matchedUnknown) { }

    /** Expected/actual nulls are legitimate facts; comparison distinguishes them from unavailable output. */
    @Builder
    public record FieldComparison(@NonNull String field, @Nullable Object expected, @Nullable Object actual,
            @NonNull Comparison comparison) { }

    /**
     * A null total means no provider reported this counter; partial sums carry their own coverage count.
     * @param reportedCases number of calls providing this specific counter, including explicitly reported zero
     * @param total sum in long precision, or null if no call provided a value; never inferred from other counters
     */
    @Builder
    public record TokenCounter(int reportedCases, @Nullable Long total) { }

    /** Each counter has independent reporting coverage; do not infer total tokens from incomplete other counters. */
    @Builder
    public record Usage(@NonNull TokenCounter promptTokens, @NonNull TokenCounter completionTokens,
            @NonNull TokenCounter totalTokens, @NonNull TokenCounter reasoningTokens) { }

    /**
     * Per-case immutable evidence. Null schemaAccepted/findings mean the reader was not reached, not zero violations.
     * No raw provider response, exception message or reasoning text is retained in the report.
     */
    @Builder
    public record CaseResult(@NonNull String id, @NonNull List<String> tags, @NonNull String reviewNote,
            @NonNull String documentSha256, @NonNull String expectedSha256, @NonNull Outcome outcome,
            @Nullable Boolean schemaAccepted, @Nullable Integer findings, @NonNull AnnouncementExtractionOutput expected,
            @Nullable AnnouncementExtractionOutput actual, @Nullable String error, long elapsedMillis,
            @Nullable ModelReplyDiagnostics diagnostics, @Nullable List<ResponseViolation> validationFindings,
            @NonNull List<FieldComparison> fields) {
        public CaseResult {
            tags = List.copyOf(tags);
            fields = List.copyOf(fields);
            if (validationFindings != null) validationFindings = List.copyOf(validationFindings);
        }
    }
}
