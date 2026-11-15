package example.announcement;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;

/** UTF-8 JSON for automation and escaped Markdown for human review; report files contain fictional data only. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AnnouncementQualityReportWriter {
    /**
     * Writes both reports before the CLI checks success, so unsuccessful model runs remain reviewable.
     * An output failure propagates; callers must not report a successful run without its evidence.
     * Existing report files in the selected directory are replaced; use a new directory to retain a previous run.
     * @param directory caller-selected output directory, separate from authored fixtures
     * @param report complete successful/unsuccessful case evidence
     * @throws IOException if the report destination cannot be written
     */
    public static void write(@NonNull Path directory, @NonNull AnnouncementQualityReport report) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("quality-report.json"), JsonMapper.builder().build().writeValueAsString(report) + "\n", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("quality-report.md"), markdown(report), StandardCharsets.UTF_8);
    }

    static String markdown(AnnouncementQualityReport report) {
        var text = new StringBuilder("# Announcement regression report\n\n");
        text.append("Mode: **").append(report.context().mode()).append("**. ");
        text.append(report.context().mode() == AnnouncementQualityReport.Mode.SYNTHETIC_REPLAY
                ? "Expected answers were replayed. Scores verify the evaluation flow; they do not measure model quality.\n\n"
                : "Scores describe only this reviewed fictional corpus, not general model accuracy.\n\n");
        text.append("Created: ").append(cell(report.createdAt())).append("  \nCorpus: v").append(report.corpusVersion())
                .append(" / `").append(report.corpusSha256()).append("`  \nContract: ").append(cell(report.contractVersion()))
                .append(" / `").append(report.contractFingerprint()).append("`\n\n");
        var settings = report.context().settings();
        if (settings != null) text.append("Model: `").append(settings.model()).append("`  \nEndpoint: ").append(cell(settings.baseUrl()))
                .append("  \nTemperature: ").append(report.context().temperature()).append("; retries: ")
                .append(report.context().maxRetries()).append("; max tokens: ").append(settings.maxTokens())
                .append("; timeout per case: ").append(settings.timeout().toSeconds()).append(" s.\n\n");
        var summary = report.summary();
        text.append("Repair-free case passes: **").append(summary.passedCases()).append('/').append(summary.totalCases())
                .append("**. Schema-accepted cases: ").append(summary.schemaAcceptedCases()).append('/').append(summary.totalCases())
                .append(". Matching normalized fields: ").append(summary.matchingFields()).append('/').append(summary.totalFields())
                .append(". Extraction time: ").append(summary.elapsedMillis()).append(" ms.\n\n")
                .append("Unavailable fields count toward totals; a rejected answer cannot earn credit for expected nulls. ")
                .append("A repaired answer can match normalized fields and still fail the case.\n\n")
                .append("| Outcome | Cases |\n| --- | ---: |\n");
        summary.outcomes().forEach((outcome, count) -> text.append('|').append(outcome).append('|').append(count).append("|\n"));
        text.append("\n| Field | Matched / total | Available | Known facts matched / expected | Unknowns preserved / expected |\n| --- | ---: | ---: | ---: | ---: |\n");
        for (var field : report.fields()) text.append('|').append(field.field()).append('|').append(field.matched()).append('/').append(field.total())
                .append('|').append(field.available()).append('|').append(field.matchedKnown()).append('/').append(field.expectedKnown())
                .append('|').append(field.matchedUnknown()).append('/').append(field.expectedUnknown()).append("|\n");
        text.append("\n## Provider usage\n\nCounters are sums of reported values only; null means unknown, never zero.\n\n")
                .append("| Counter | Sum | Cases reporting / total |\n| --- | ---: | ---: |\n");
        counter(text, "Prompt tokens", report.usage().promptTokens(), summary.totalCases());
        counter(text, "Completion tokens", report.usage().completionTokens(), summary.totalCases());
        counter(text, "Total tokens", report.usage().totalTokens(), summary.totalCases());
        counter(text, "Reasoning tokens", report.usage().reasoningTokens(), summary.totalCases());
        for (var item : report.cases()) {
            text.append("\n## ").append(item.id()).append("\n\n").append(cell(item.reviewNote())).append("\n\n")
                    .append("Tags: ").append(cell(String.join(", ", item.tags()))).append(". Outcome: **").append(item.outcome())
                    .append("**; schema accepted: ").append(item.schemaAccepted()).append("; findings: ").append(item.findings())
                    .append("; elapsed: ").append(item.elapsedMillis()).append(" ms.\n\n")
                    .append("Document SHA-256: `").append(item.documentSha256()).append("`  \nExpected JSON SHA-256: `")
                    .append(item.expectedSha256()).append("`\n\n");
            var diagnostics = item.diagnostics();
            text.append("Finish reason: ").append(cell(diagnostics == null ? null : diagnostics.finishReason()))
                    .append("; error category: ").append(cell(item.error())).append(".\n\n");
            var failure = item.failureDiagnostics();
            var timing = item.callTiming();
            if (timing != null) text.append("Client interval UTC: ").append(cell(timing.startedAtUtc())).append(" → ")
                    .append(cell(timing.finishedAtUtc())).append(".\n\n");
            if (failure != null) text.append("Failure classification: ").append(failure.category())
                    .append("; cause classes: ").append(cell(String.join(" → ", failure.causeClasses())))
                    .append("; complete cause chain: ").append(failure.causeChainComplete())
                    .append("; HTTP status: ").append(failure.httpStatus())
                    .append("; observed at UTC: ").append(cell(failure.observedAtUtc())).append(".\n\n");
            var findings = item.validationFindings();
            if (findings != null && !findings.isEmpty()) {
                text.append("| Finding pointer | Rule | Action |\n| --- | --- | --- |\n");
                for (var finding : findings) text.append('|').append(cell(finding.pointer())).append('|')
                        .append(cell(finding.rule())).append('|').append(finding.action()).append("|\n");
                text.append('\n');
            }
            text.append("| Field | Expected | Actual | Comparison |\n| --- | --- | --- | --- |\n");
            for (var field : item.fields()) text.append('|').append(field.field()).append('|').append(cell(field.expected()))
                    .append('|').append(field.comparison() == AnnouncementQualityReport.Comparison.UNAVAILABLE ? "unavailable" : cell(field.actual()))
                    .append('|').append(field.comparison()).append("|\n");
        }
        return text.toString();
    }

    private static void counter(StringBuilder text, String name, AnnouncementQualityReport.TokenCounter counter, int cases) {
        text.append('|').append(name).append('|').append(counter.total()).append('|').append(counter.reportedCases()).append('/').append(cases).append("|\n");
    }

    // Provider-returned strings are data: prevent HTML/Markdown table injection in a locally opened report.
    private static String cell(@Nullable Object value) {
        var escaped = new StringBuilder();
        String.valueOf(value).codePoints().forEach(code -> {
            switch (code) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '|' -> escaped.append("&#124;");
                case '`' -> escaped.append("&#96;");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\\', '[', ']', '*', '_' -> escaped.append('\\').appendCodePoint(code);
                default -> {
                    int type = Character.getType(code);
                    if (Character.isISOControl(code) || type == Character.FORMAT
                            || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR) escaped.append("[control]");
                    else escaped.appendCodePoint(code);
                }
            }
        });
        return escaped.toString();
    }
}
