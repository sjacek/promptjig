package example.announcement;

import example.announcement.generated.AnnouncementExtractionOutput;
import example.announcement.generated.AnnouncementExtractionPromptContract;
import io.github.sjacek.promptjig.springai.PromptJsonBinding;
import io.github.sjacek.promptjig.springai.PromptReadResult;
import java.nio.charset.StandardCharsets;
import java.io.PrintStream;
import java.util.Map;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import org.springframework.core.io.ClassPathResource;

/**
 * Console entry point for the bundled fictional public announcement.
 * Explicit local arguments select inference; environment settings alone cannot select it.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PilotApplication {
    /**
     * Runs the demo using process environment/output; installed launchers use the same classpath resources.
     * @param args no arguments for the synthetic demo, --local-model, --local-suite or --local-case=fixture-id
     * @throws Exception if configuration, transport or response validation prevents completion
     */
    public static void main(@NonNull String[] args) throws Exception {
        run(args, System.getenv(), System.out);
    }

    // Explicit inputs keep CLI routing testable without changing global environment or System.out.
    // The output stream belongs to the caller; only an opened local model session is closed here.
    static void run(@NonNull String[] args, @NonNull Map<String, String> environment, @NonNull PrintStream output) throws Exception {
        boolean singleCase = args.length == 1 && args[0].startsWith("--local-case=");
        String caseId = singleCase ? args[0].substring("--local-case=".length()) : "";
        if (args.length > 1 || (singleCase && !AnnouncementScenarioSuite.CASES.contains(caseId))
                || (args.length == 1 && !singleCase && !args[0].equals("--local-model") && !args[0].equals("--local-suite"))) {
            throw new IllegalArgumentException("Supported arguments: --local-model, --local-suite or --local-case=known-fixture-id");
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
}
