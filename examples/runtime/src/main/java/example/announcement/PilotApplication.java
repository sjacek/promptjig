package example.announcement;

import example.announcement.generated.AnnouncementExtractionOutput;
import example.announcement.generated.AnnouncementExtractionPromptContract;
import io.github.sjacek.promptjig.springai.PromptJsonBinding;
import io.github.sjacek.promptjig.springai.PromptReadResult;
import java.nio.charset.StandardCharsets;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import org.springframework.core.io.ClassPathResource;
import org.springframework.ai.chat.model.ChatModel;

/**
 * Console entry point for the bundled fictional public announcement.
 * Explicit local arguments select inference; environment settings alone cannot select it.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PilotApplication {
    /**
     * Runs the demo using process environment/output; installed launchers use the same classpath resources.
     * @param args no arguments for the demo; explicit local modes or --regression-report=directory for a replay report
     * @throws Exception if configuration, transport or response validation prevents completion
     */
    public static void main(@NonNull String[] args) throws Exception {
        run(args, System.getenv(), System.out);
    }

    // Explicit inputs keep CLI routing testable without changing global environment or System.out.
    // The output stream belongs to the caller; only an opened local model session is closed here.
    static void run(@NonNull String[] args, @NonNull Map<String, String> environment, @NonNull PrintStream output) throws Exception {
        boolean replayReport = args.length == 1 && args[0].startsWith("--regression-report=");
        boolean localReport = args.length == 1 && args[0].startsWith("--local-regression-report=");
        if (replayReport || localReport) {
            var directory = args[0].substring(args[0].indexOf('=') + 1);
            if (directory.isBlank()) throw new IllegalArgumentException("Set a regression report directory");
            // Preflight every fixture and the output location before opening a transport or spending model time.
            var corpus = AnnouncementRegressionCorpus.load();
            var destination = Path.of(directory);
            Files.createDirectories(destination);
            if (localReport) {
                var settings = LocalModelSettings.fromEnvironment(environment);
                try (var session = settings.open()) {
                    regression(corpus, session.model(), AnnouncementQualityReport.RunContext.builder()
                            .mode(AnnouncementQualityReport.Mode.LOCAL_MODEL).settings(settings).build(), destination, output);
                }
            } else {
                regression(corpus, corpus.replayModel(), AnnouncementQualityReport.RunContext.builder()
                        .mode(AnnouncementQualityReport.Mode.SYNTHETIC_REPLAY).build(), destination, output);
            }
            return;
        }
        boolean singleCase = args.length == 1 && args[0].startsWith("--local-case=");
        String caseId = singleCase ? args[0].substring("--local-case=".length()) : "";
        if (args.length > 1 || (singleCase && !AnnouncementScenarioSuite.CASES.contains(caseId))
                || (args.length == 1 && !singleCase && !args[0].equals("--local-model") && !args[0].equals("--local-suite"))) {
            throw new IllegalArgumentException("Supported arguments: --local-model, --local-suite, --local-case=known-fixture-id, "
                    + "--regression-report=directory or --local-regression-report=directory");
        }
        if (singleCase || (args.length == 1 && args[0].equals("--local-suite"))) {
            var settings = LocalModelSettings.fromEnvironment(environment);
            output.println("mode=local-suite; model=" + settings.model() + "; contract="
                    + AnnouncementExtractionPromptContract.VERSION_REF + "; fingerprint="
                    + AnnouncementExtractionPromptContract.FINGERPRINT + "; maxTokens=" + settings.maxTokens());
            try (var session = settings.open()) {
                var suite = AnnouncementScenarioSuite.builder().model(session.model()).build();
                var results = singleCase ? suite.runCase(caseId, output) : suite.run(output);
                long passed = results.stream().filter(AnnouncementScenarioSuite.ScenarioResult::passed).count();
                output.println("cases=" + results.size() + "; passed=" + passed + "; failed=" + (results.size() - passed));
                if (passed != results.size()) throw new IllegalStateException("Local announcement suite failed; inspect per-case evidence");
            }
            return;
        }
        String document = new ClassPathResource("documents/public-event-testdata.txt").getContentAsString(StandardCharsets.UTF_8);
        PromptReadResult<AnnouncementExtractionOutput> result;
        boolean local = args.length == 1;
        if (local) {
            try (var session = LocalModelSettings.fromEnvironment(environment).open()) {
                result = AnnouncementPilot.builder().model(session.model()).build().extract(document);
            }
        } else {
            String reply = new ClassPathResource("responses/public-event-testdata.json").getContentAsString(StandardCharsets.UTF_8);
            result = AnnouncementPilot.builder().model(SyntheticChatModel.builder().response(reply).build()).build().extract(document);
        }
        output.println("mode=" + (local ? "local-model" : "synthetic") + "; contract="
                + AnnouncementExtractionPromptContract.VERSION_REF + "; accepted=" + result.accepted()
                + "; findings=" + result.violations().size());
        var value = result.value();
        if (value == null) throw new IllegalStateException("Model response rejected by the announcement contract");
        output.println(PromptJsonBinding.jackson3().write(value));
    }

    /**
     * Runs a complete preflighted corpus and persists evidence before enforcing repair-free success.
     * Failures of individual calls are case outcomes; failures of evidence creation/writing propagate.
     * @param corpus reviewed source/oracle pairs
     * @param model caller-owned model; this method does not open or close its transport
     * @param context provenance matching the selected transport settings
     * @param destination caller-selected report directory
     * @param output caller-owned progress stream, left open on success and failure
     * @throws Exception if evidence cannot be written, or if the completed evaluation has unsuccessful cases
     */
    static void regression(@NonNull AnnouncementRegressionCorpus corpus, @NonNull ChatModel model,
            @NonNull AnnouncementQualityReport.RunContext context, @NonNull Path destination,
            @NonNull PrintStream output) throws Exception {
        output.println("mode=" + context.mode() + "; corpus=" + corpus.sha256());
        var results = AnnouncementScenarioSuite.builder().model(model).build().runRegression(corpus, output);
        var report = AnnouncementQualityReport.create(corpus, context, results);
        AnnouncementQualityReportWriter.write(destination, report);
        output.println("cases=" + report.summary().totalCases() + "; passed=" + report.summary().passedCases()
                + "; report=" + destination.resolve("quality-report.json").toAbsolutePath());
        if (report.summary().passedCases() != report.summary().totalCases()) {
            throw new IllegalStateException("Announcement regression failed; inspect saved quality reports");
        }
    }
}
