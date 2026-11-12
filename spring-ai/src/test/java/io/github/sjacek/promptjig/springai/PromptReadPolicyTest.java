package io.github.sjacek.promptjig.springai;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.sjacek.promptjig.core.PromptContract;
import io.github.sjacek.promptjig.core.ResponseViolation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import lombok.Builder;
import lombok.NonNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import static org.junit.jupiter.api.Assertions.*;

/** Migration checks use real resource loading and both codecs; schema rejection must precede binding. */
@NullMarked
class PromptReadPolicyTest {
    private static final String ROOT = "prompts/patient/disability/";
    private static final String CONTRACT = "certificate-extraction.prompt.yaml";
    @TempDir Path directory;

    @ParameterizedTest
    @CsvSource({"v2, omitted", "v2, true", "v2, false", "v3, omitted", "v3, true", "v3, false"})
    void onlyExplicitFalsePermitsRepairs(String jackson, String mode) throws Exception {
        var contract = load(mode);
        var reads = new AtomicInteger();
        var runtime = runtime(jackson, reads);
        var fingerprint = contract.fingerprint();
        // Each case needs a different repair: type, enum, date, pattern/item, unknown key, and required fills.
        List<Consumer<ObjectNode>> changes = List.of(
                node -> node.put("permanent", "true"),
                node -> node.put("degree", "synthetic-secret"),
                node -> node.put("issuedAt", "2025-02-30"),
                node -> node.putArray("symbolCodes").add("05-R").add("synthetic-secret"),
                node -> node.put("synthetic-secret-key", "synthetic-secret"),
                node -> node.remove("patientSurname"),
                node -> node.remove("indicationCodes"));
        boolean repairing = mode.equals("false");
        for (var change : changes) {
            ObjectNode response = validResponse();
            change.accept(response);
            var result = runtime.read(contract, response.toString(), Map.class);
            assertEquals(repairing, result.accepted(), result.violations().toString());
            assertFalse(result.violations().isEmpty());
            if (repairing) {
                assertNotNull(result.value());
                assertTrue(result.violations().stream().noneMatch(v -> v.action() == ResponseViolation.Action.REJECTED));
            } else {
                assertNull(result.value());
                assertTrue(result.violations().stream().allMatch(v -> v.action() == ResponseViolation.Action.REJECTED));
            }
            assertFalse(result.toString().contains("synthetic-secret"));
            assertFalse(result.violations().toString().contains("synthetic-secret"));
        }
        assertEquals(repairing ? changes.size() : 0, reads.get());
        assertEquals(fingerprint, contract.fingerprint());
        // Selecting the default policy must not insert strict into the authored snapshot or its fingerprint.
        assertEquals(!mode.equals("omitted"), contract.contract().document().path("output").has("strict"));
    }

    @ParameterizedTest
    @CsvSource({"v2, omitted", "v2, true", "v2, false", "v3, omitted", "v3, true", "v3, false"})
    void validResponsesAndAllowedNullsAreAcceptedInEveryMode(String jackson, String mode) throws Exception {
        var reads = new AtomicInteger();
        var result = runtime(jackson, reads).read(load(mode), validResponse().toString(), Map.class);
        assertTrue(result.accepted());
        assertTrue(result.violations().isEmpty());
        var value = result.value();
        if (value == null) throw new AssertionError("Expected accepted synthetic output");
        assertEquals("MODERATE", value.get("degree"));
        assertNull(value.get("patientSurname"));
        assertEquals(List.of("05-R"), value.get("symbolCodes"));
        assertEquals(1, reads.get());
    }

    @ParameterizedTest
    @CsvSource({"v2, omitted", "v2, true", "v2, false", "v3, omitted", "v3, true", "v3, false"})
    void malformedAndStructurallyInvalidResponsesNeverReachBinding(String jackson, String mode) throws Exception {
        var reads = new AtomicInteger();
        var runtime = runtime(jackson, reads);
        var contract = load(mode);
        var wrongArray = validResponse(); wrongArray.put("symbolCodes", 42);
        for (String response : List.of("", "{", "null", "[]", "{} {}", wrongArray.toString())) {
            var result = runtime.read(contract, response, Map.class);
            assertFalse(result.accepted());
            assertNull(result.value());
        }
        assertEquals(0, reads.get());
    }

    private RuntimePromptContract load(String mode) throws Exception {
        var source = PromptContractRegistry.load(new ClassPathResource(ROOT + CONTRACT))
                .get("patient.disability.certificate-extraction").contract();
        var output = (ObjectNode) source.document().path("output");
        if (mode.equals("omitted")) output.remove("strict");
        else output.put("strict", Boolean.parseBoolean(mode));
        for (var template : source.templates().entrySet())
            Files.writeString(directory.resolve(template.getKey()), template.getValue());
        Path file = directory.resolve(CONTRACT);
        PromptContract.JSON.writeValue(file.toFile(), source.document());
        return PromptContractRegistry.load(new FileSystemResource(file)).get("patient.disability.certificate-extraction");
    }

    private static ObjectNode validResponse() throws Exception {
        String json = new ClassPathResource("fixtures/valid-output.json").getContentAsString(StandardCharsets.UTF_8);
        var node = PromptContract.JSON.readTree(json);
        if (!(node instanceof ObjectNode object)) throw new AssertionError("Expected synthetic response object");
        return object;
    }

    private static PromptRuntime runtime(String jackson, AtomicInteger reads) {
        var binding = CountingBinding.builder().delegate(jackson.equals("v2")
                ? PromptJsonBinding.jackson2() : PromptJsonBinding.jackson3()).reads(reads).build();
        return PromptRuntime.builder().binding(binding).build();
    }

    /** Count real codec calls so a rejected response cannot silently produce a partial bound model. */
    @Builder
    private record CountingBinding(@NonNull PromptJsonBinding delegate, @NonNull AtomicInteger reads) implements PromptJsonBinding {
        @Override public String write(@NonNull Object input) throws Exception { return delegate.write(input); }
        @Override public @Nullable Object read(@NonNull String json, @NonNull Class<?> type) throws Exception {
            reads.incrementAndGet();
            return delegate.read(json, type);
        }
    }
}
