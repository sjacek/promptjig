package example.announcement;

import example.announcement.generated.AnnouncementExtractionOutput;
import io.github.sjacek.promptjig.springai.PromptJsonBinding;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.core.io.ClassPathResource;

/**
 * Runs the bundled fictional announcements independently, comparing all generated fields with reviewed fixtures.
 * A schema-accepted response is insufficient: repairs and fact mismatches fail the scenario as well.
 * The public fixtures are deliberately printable; this runner is not intended for confidential input.
 */
public final class AnnouncementScenarioSuite {
    static final List<String> CASES = List.of("public-event-testdata", "online-event-testdata",
            "paid-event-testdata", "missing-details-testdata", "missing-price-testdata");
    private final AnnouncementPilot pilot;

    @Builder
    private AnnouncementScenarioSuite(@NonNull ChatModel model) {
        pilot = AnnouncementPilot.builder().model(model).build();
    }

    /**
     * Runs each case once, with no conversation history or automatic retry. Provider failures are recorded
     * separately from validator rejection and do not prevent the remaining announcements from being checked.
     * Invalid/missing bundled fixtures fail immediately rather than being mistaken for model failures.
     * @param output caller-owned progress and JSON evidence stream, left open
     * @return immutable results; every result must pass for the local suite to succeed
     * @throws Exception if bundled fixtures cannot be loaded or evidence cannot be serialized
     */
    public List<ScenarioResult> run(@NonNull PrintStream output) throws Exception {
        return runCases(CASES, output);
    }

    /**
     * Runs one known fixture for a controlled diagnostic comparison with another token budget.
     * @param id one bundled fixture identifier; arbitrary classpath paths are rejected
     * @param output caller-owned evidence stream
     * @return one result using the same evaluation rules as the full suite
     * @throws Exception if the fixture or evidence cannot be read/written
     */
    public List<ScenarioResult> runCase(@NonNull String id, @NonNull PrintStream output) throws Exception {
        if (!CASES.contains(id)) throw new IllegalArgumentException("Unknown announcement case: " + id);
        return runCases(List.of(id), output);
    }

    private List<ScenarioResult> runCases(List<String> cases, PrintStream output) throws Exception {
        var binding = PromptJsonBinding.jackson3();
        var results = new ArrayList<ScenarioResult>();
        for (var id : cases) {
            var document = resource("documents/" + id + ".txt");
            var decoded = binding.read(resource("responses/" + id + ".json"), AnnouncementExtractionOutput.class);
            if (!(decoded instanceof AnnouncementExtractionOutput expected)) {
                throw new IllegalStateException("Missing expected output for " + id);
            }
            output.println("case=" + id + "; status=running");
            long started = System.nanoTime();
            var result = ScenarioResult.builder().id(id).expected(expected);
            try {
                var response = pilot.extract(document, metadata -> result.diagnostics(metadata));
                result.accepted(response.accepted()).findings(response.violations().size())
                        .actual(response.value()).matchesExpected(expected.equals(response.value()));
            } catch (RuntimeException exception) {
                // Retain a failure category without printing a provider body or pretending it was a schema rejection.
                result.error(exception.getClass().getSimpleName());
            }
            var finished = result.elapsedMillis((System.nanoTime() - started) / 1_000_000).build();
            results.add(finished);
            output.println(binding.write(finished));
        }
        return List.copyOf(results);
    }

    private static String resource(String path) throws Exception {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * Per-case evidence, including explicit nulls for facts absent from the source.
     * @param id bundled fixture identifier
     * @param expected reviewed generated output
     * @param actual normalized generated output, or null on rejection/provider failure
     * @param accepted whether the runtime bound the response
     * @param findings number of validation findings, including repairs
     * @param matchesExpected whether all five fields equal the reviewed fixture
     * @param error provider/runtime exception category, or null when the reader returned normally
     * @param elapsedMillis elapsed extraction time, excluding fixture loading
     * @param diagnostics provider metadata, including finish reason before response validation; null on transport failure
     */
    @Builder
    public record ScenarioResult(@NonNull String id, @NonNull AnnouncementExtractionOutput expected,
            @Nullable AnnouncementExtractionOutput actual, boolean accepted, int findings,
            boolean matchesExpected, @Nullable String error, long elapsedMillis, @Nullable ModelReplyDiagnostics diagnostics) {
        /** {@return whether extraction matched the source without repair, rejection or provider failure} */
        public boolean passed() { return accepted && matchesExpected && findings == 0 && error == null; }
    }
}
