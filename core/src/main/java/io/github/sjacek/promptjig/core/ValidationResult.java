package io.github.sjacek.promptjig.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Aggregated diagnostics, sorted independently of filesystem traversal order.
 *
 * @param diagnostics findings, copied into a sorted, duplicate-free, unmodifiable list
 * @param contracts number of discovered contract files, including invalid files
 */
public record ValidationResult(List<Diagnostic> diagnostics, int contracts) {
    /**
     * Normalizes the diagnostic collection for deterministic reports.
     * @param diagnostics findings to sort and deduplicate
     * @param contracts number of discovered contract files
     */
    public ValidationResult {
        diagnostics = diagnostics.stream().distinct().sorted().toList();
    }

    /** {@return whether validation produced no errors; informational findings do not fail a build} */
    public boolean valid() {
        return diagnostics.stream().noneMatch(diagnostic -> diagnostic.severity() == Diagnostic.Severity.ERROR);
    }

    /**
     * Writes {@code validation.json} (report format v1) and UTF-8 {@code validation.txt}, replacing previous reports.
     * Report content contains the supplied portable locations and no timestamps.
     *
     * @param directory report directory, created if absent
     * @throws IOException if either report cannot be written
     */
    public void writeReports(Path directory) throws IOException {
        Files.createDirectories(directory);
        var report = PromptContract.JSON.createObjectNode().put("reportVersion", 1).put("valid", valid()).put("contracts", contracts);
        report.set("diagnostics", PromptContract.JSON.valueToTree(diagnostics));
        PromptContract.JSON.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("validation.json").toFile(), report);
        String text = diagnostics.stream().map(Diagnostic::text).collect(java.util.stream.Collectors.joining("\n"));
        Files.writeString(directory.resolve("validation.txt"), text.isEmpty() ? "No violations.\n" : text + "\n",
                StandardCharsets.UTF_8);
    }
}
