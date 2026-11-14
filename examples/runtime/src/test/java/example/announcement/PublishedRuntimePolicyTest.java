package example.announcement;

import com.fasterxml.jackson.databind.node.ObjectNode;
import example.announcement.generated.AnnouncementExtractionOutput;
import example.announcement.generated.AnnouncementExtractionPromptContract;
import io.github.sjacek.promptjig.springai.PromptContractRegistry;
import io.github.sjacek.promptjig.springai.PromptJsonBinding;
import io.github.sjacek.promptjig.springai.PromptRuntime;
import io.github.sjacek.promptjig.springai.RuntimePromptContract;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the published reader's policy with generated models, without changing the frozen pilot resources. */
class PublishedRuntimePolicyTest {
    @ParameterizedTest
    @ValueSource(strings = {"absent", "true", "false"})
    void rejectsInvalidFieldsByDefaultAndRepairsOnlyAfterExplicitOptIn(String strict) {
        var authored = PromptContractRegistry.load(new ClassPathResource(AnnouncementExtractionPromptContract.RESOURCE))
                .get(AnnouncementExtractionPromptContract.ID);
        // Registry snapshots are defensive copies; this synthetic variant never rewrites the contract or its lock.
        var copy = authored.contract();
        var output = assertInstanceOf(ObjectNode.class, copy.document().path("output"));
        if (strict.equals("absent")) output.remove("strict");
        else output.put("strict", Boolean.parseBoolean(strict));
        var variant = RuntimePromptContract.builder().contract(copy).build();
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
}
