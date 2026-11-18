package example.announcement;

import com.fasterxml.jackson.databind.node.ObjectNode;
import example.announcement.generated.AnnouncementExtractionInput;
import example.announcement.generated.AnnouncementExtractionOutput;
import example.announcement.generated.AnnouncementExtractionPromptContract;
import io.github.sjacek.promptjig.springai.PromptContractRegistry;
import io.github.sjacek.promptjig.springai.PromptJsonBinding;
import io.github.sjacek.promptjig.springai.PromptRuntime;
import io.github.sjacek.promptjig.springai.RuntimePromptContract;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import static org.junit.jupiter.api.Assertions.*;

/** Checks the contract's input/output boundary using published libraries and generated models.
 * Policy variants are isolated snapshots; the authored contract and its frozen lock are never rewritten. */
class PublishedRuntimePolicyTest {
    @ParameterizedTest
    @ValueSource(strings = {"absent", "true", "false"})
    void rejectsInvalidFieldsByDefaultAndRepairsOnlyAfterExplicitOptIn(String strict) {
        var authored = authored();
        var variant = withOutputPolicy(authored, strict);
        var runtime = PromptRuntime.builder().binding(PromptJsonBinding.jackson3()).build();
        var result = runtime.read(variant, """
                {"title":"Fictional public event", "date":"2026-12-10", "location":null,
                 "format":"UNKNOWN_TESTDATA", "free":true}
                """, AnnouncementExtractionOutput.class);

        assertFalse(result.violations().isEmpty());
        assertTrue(result.violations().stream().anyMatch(finding -> finding.pointer().equals("/format")));
        assertFalse(result.violations().toString().contains("UNKNOWN_TESTDATA"));
        if (strict.equals("false")) {
            assertTrue(result.accepted());
            var value = assertInstanceOf(AnnouncementExtractionOutput.class, result.value());
            assertNull(value.format());
            assertEquals("Fictional public event", value.title());
        } else {
            assertFalse(result.accepted());
            assertNull(result.value());
        }
        assertFalse(authored.contract().document().path("output").path("strict").asBoolean());
        assertEquals(AnnouncementExtractionPromptContract.FINGERPRINT, authored.fingerprint());
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "true", "false"})
    void promptInputRemainsStrictRegardlessOfResponseRepairPolicy(String strict) {
        var authored = authored();
        var variant = withOutputPolicy(authored, strict);
        var runtime = PromptRuntime.builder().binding(PromptJsonBinding.jackson3()).build();
        String document = "Fikcyjne ogłoszenie-testdata {documentText}";
        var input = new AnnouncementExtractionInput(document);
        var expected = runtime.prompt(variant, input);
        String user = expected.get(1).getText();
        if (user == null) throw new AssertionError("Missing rendered document");
        assertTrue(user.contains(document));

        Map<String, @Nullable Object> nullField = new LinkedHashMap<>();
        nullField.put("documentText", null);
        // The Java input type helps callers construct data; the authored schema still governs acceptance.
        // In particular, repairing nullable response fields must never repair or coerce prompt inputs.
        for (Object invalid : List.of(Map.of(), nullField, Map.of("documentText", 42),
                Map.of("documentText", true), Map.of("documentText", List.of("wrong-shape-testdata")),
                Map.of("documentText", "public-testdata", "unknown-field-testdata", "discarded-testdata"),
                Map.of("documentText", "public-testdata", "contract", Map.of("output", "override-testdata")))) {
            var failure = assertThrows(IllegalArgumentException.class, () -> runtime.prompt(variant, invalid));
            assertEquals("Prompt input or rendering is invalid", failure.getMessage());
            assertNull(failure.getCause());
            assertEquals(0, failure.getSuppressed().length);
        }
        assertEquals(expected, runtime.prompt(variant, input), "Rejection must not affect the next valid render");
        assertEquals(AnnouncementExtractionPromptContract.FINGERPRINT, authored.fingerprint());
        assertFalse(authored.contract().document().path("output").path("strict").asBoolean());
    }

    private static RuntimePromptContract authored() {
        return PromptContractRegistry.load(new ClassPathResource(AnnouncementExtractionPromptContract.RESOURCE))
                .get(AnnouncementExtractionPromptContract.ID);
    }

    private static RuntimePromptContract withOutputPolicy(RuntimePromptContract authored, String strict) {
        var copy = authored.contract();
        var output = assertInstanceOf(ObjectNode.class, copy.document().path("output"));
        if (strict.equals("absent")) output.remove("strict");
        else output.put("strict", Boolean.parseBoolean(strict));
        return RuntimePromptContract.builder().contract(copy).build();
    }
}
