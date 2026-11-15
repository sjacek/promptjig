package example.announcement;

import example.announcement.generated.AnnouncementExtractionOutput;
import example.announcement.generated.AnnouncementExtractionPromptContract;
import io.github.sjacek.promptjig.springai.PromptContractRegistry;
import io.github.sjacek.promptjig.springai.PromptJsonBinding;
import io.github.sjacek.promptjig.springai.PromptRuntime;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import lombok.Builder;
import lombok.NonNull;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reviewed fictional public documents and their expected answers, independent of model output.
 * All fixtures are validated before opening a transport. The content digest includes the manifest,
 * UTF-8 document content and expected JSON after LF normalization, so Windows checkouts retain
 * the same identity and changing an oracle or its source changes the run identity.
 * @param version manually maintained corpus revision
 * @param sha256 content identity of the complete ordered corpus
 * @param cases immutable reviewed cases in execution order
 */
@Builder
public record AnnouncementRegressionCorpus(int version, @NonNull String sha256, @NonNull List<Case> cases) {
    public AnnouncementRegressionCorpus {
        if (version < 1 || cases.isEmpty()) throw new IllegalArgumentException("Corpus must have a positive version and cases");
        cases = List.copyOf(cases);
    }

    /** {@return the bundled, preflight-validated corpus; performs no inference or network access} */
    public static AnnouncementRegressionCorpus load() throws IOException {
        return load(resource("regression/announcements-testdata.json"), AnnouncementRegressionCorpus::resource);
    }

    // The version identifies a reviewed dataset revision, not a model or prompt contract version.
    // Validate all entries before returning: callers must not open a model after only a partial preflight.
    // A resource reader seam permits missing/corrupt-fixture tests without rewriting authored resources.
    static AnnouncementRegressionCorpus load(@NonNull String manifest, @NonNull ResourceReader resources) throws IOException {
        var canonicalManifest = normalize(manifest);
        var definition = JsonMapper.builder().build().readValue(canonicalManifest, Definition.class);
        if (definition == null || definition.version() < 1 || definition.cases().isEmpty()) {
            throw new IllegalArgumentException("Missing corpus definition or cases");
        }
        var contract = PromptContractRegistry.load(new ClassPathResource(AnnouncementExtractionPromptContract.RESOURCE))
                .get(AnnouncementExtractionPromptContract.ID);
        var runtime = PromptRuntime.builder().binding(PromptJsonBinding.jackson3()).build();
        var digest = digest();
        update(digest, canonicalManifest);
        var ids = new HashSet<String>();
        var cases = new ArrayList<Case>();
        for (var entry : definition.cases()) {
            if (!entry.id().matches("[a-z0-9]+(?:-[a-z0-9]+)*-testdata") || entry.id().length() > 96
                    || !ids.add(entry.id()) || entry.tags().isEmpty() || entry.reviewNote().isBlank()
                    || entry.tags().stream().anyMatch(tag -> !tag.matches("[a-z]+(?:-[a-z]+)*"))) {
                throw new IllegalArgumentException("Invalid or duplicate corpus entry");
            }
            var document = normalize(resources.read("documents/" + entry.id() + ".txt"));
            var expectedJson = normalize(resources.read("responses/" + entry.id() + ".json"));
            var decoded = runtime.read(contract, expectedJson, AnnouncementExtractionOutput.class);
            var expected = decoded.value();
            // A repaired oracle would hide a broken fixture. Even intentional nulls must be explicit.
            if (document.isBlank() || !decoded.accepted() || expected == null || !decoded.violations().isEmpty()) {
                throw new IllegalArgumentException("Invalid reviewed fixture: " + entry.id());
            }
            update(digest, document);
            update(digest, expectedJson);
            cases.add(Case.builder().id(entry.id()).tags(entry.tags()).reviewNote(entry.reviewNote())
                    .document(document).expectedJson(expectedJson).expected(expected)
                    .documentSha256(hash(document)).expectedSha256(hash(expectedJson)).build());
        }
        return builder().version(definition.version()).sha256(HexFormat.of().formatHex(digest.digest())).cases(cases).build();
    }

    /**
     * Replays the oracle for the rendered document to verify orchestration without an inference backend.
     * A replay score is not model accuracy; real model results must use a separate run mode.
     * @return deterministic model double; rejects missing or ambiguous document selection
     */
    public ChatModel replayModel() {
        return prompt -> {
            var text = prompt.getInstructions().get(1).getText();
            if (text == null) throw new IllegalStateException("Missing rendered announcement");
            // Zero/multiple matches are fixture/harness mistakes, never permission to guess an oracle.
            var matches = cases.stream().filter(item -> text.replace("\r\n", "\n")
                    .contains(item.document().replace("\r\n", "\n").strip())).toList();
            if (matches.size() != 1) throw new IllegalStateException("Replay requires exactly one reviewed document");
            return SyntheticChatModel.builder().response(matches.get(0).expectedJson()).build().call(prompt);
        };
    }

    private static String resource(String path) throws IOException {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }

    private static String normalize(String value) { return value.replace("\r\n", "\n").replace('\r', '\n'); }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Java must provide SHA-256", exception);
        }
    }

    private static String hash(String value) {
        return HexFormat.of().formatHex(digest().digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    // Length framing prevents ambiguous concatenations of adjacent resource contents.
    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    @FunctionalInterface
    interface ResourceReader { String read(String path) throws IOException; }

    record Definition(int version, @NonNull List<Entry> cases) { }

    record Entry(@NonNull String id, @NonNull List<String> tags, @NonNull String reviewNote) { }

    /** One reviewed source/oracle pair; source text is input data, never executable prompt configuration. */
    @Builder
    public record Case(@NonNull String id, @NonNull List<String> tags, @NonNull String reviewNote,
            @NonNull String document, @NonNull String expectedJson, @NonNull AnnouncementExtractionOutput expected,
            @NonNull String documentSha256, @NonNull String expectedSha256) {
        public Case { tags = List.copyOf(tags); }
    }
}
