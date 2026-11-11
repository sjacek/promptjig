package io.github.sjacek.promptjig.springai;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.sjacek.promptjig.core.PromptContract;
import io.github.sjacek.promptjig.core.ResponseViolation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.Builder;
import lombok.NonNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.sjacek.promptjig.springai.PromptTestSupport.text;

/** Tests the boundary between validated resources, rendering and caller-supplied JSON codecs. */
@NullMarked
class PromptRuntimeEdgeCasesTest {
    private static final String ROOT = "prompts/runtime/";
    private static final String FILE = "rendering.prompt.yaml";
    @TempDir Path directory;

    private RuntimePromptContract contract() {
        return PromptContractRegistry.load(new ClassPathResource(ROOT + FILE)).get("runtime.rendering");
    }

    private PromptRuntime runtime() { return PromptRuntime.builder().binding(PromptJsonBinding.jackson3()).build(); }

    @Test void rendersNullableArrayEnumsReferenceAliasesAndAnonymousVariables() {
        var messages = runtime().prompt(contract(), Map.of("values", List.of(Map.of("name", "one"), Map.of("name", "two")),
                "suffix", "{literal}", "title", "Heading"));
        var system = text(messages.get(0));
        assertTrue(system.contains("fields=choice, enabled, tags, text"));
        assertTrue(system.contains("tags=alpha, beta"), system);
        assertTrue(system.contains("tagText=alpha, beta"));
        assertTrue(system.contains("choice=First, Second"));
        assertTrue(system.contains("enabled=true, false"));
        assertFalse(system.contains("null"));
        assertEquals("Heading:one-{literal}, two-{literal}", text(messages.get(1)).strip());
    }

    @Test void optionalMissingAttributesAndEmptyCollectionsRenderThroughConditions() {
        var absent = runtime().prompt(contract(), Map.of("values", List.of(), "suffix", "unused"));
        assertEquals("empty", text(absent.get(1)).strip());
        Map<String, @Nullable Object> input = new LinkedHashMap<>();
        input.put("values", List.of()); input.put("suffix", "unused"); input.put("title", null);
        assertEquals("empty", text(runtime().prompt(contract(), input).get(1)).strip());
        assertEquals(List.of(), contract().sensitivity());
    }

    @Test void renderingRetainsValidatedResourceSnapshotAfterFilesChange() throws Exception {
        Path source = fixture();
        var loaded = PromptContractRegistry.load(new FileSystemResource(source.resolve(FILE))).get("runtime.rendering");
        var fingerprint = loaded.fingerprint();
        Files.write(source.resolve("rendering.user.st"), new byte[]{(byte) 0xc3, (byte) 0x28});
        Files.writeString(source.resolve(FILE), "promptContract: 99");
        assertEquals("empty", text(runtime().prompt(loaded, Map.of("values", List.of(), "suffix", "unused")).get(1)).strip());
        assertEquals(fingerprint, loaded.fingerprint());
        assertThrows(IllegalArgumentException.class, () -> PromptContractRegistry.load(new FileSystemResource(source.resolve(FILE))));
    }

    @Test void malformedTemplateUtf8FailsEvenWhenContractItselfIsValid() throws Exception {
        Path source = fixture();
        Files.write(source.resolve("rendering.user.st"), new byte[]{(byte) 0xc3, (byte) 0x28});
        var failure = assertThrows(IllegalArgumentException.class,
                () -> PromptContractRegistry.load(new FileSystemResource(source.resolve(FILE))));
        assertTrue(failure.toString().contains("template-exists"));
    }

    @Test void registryCopiesCallerMapAndRejectsMissingIdsAndMismatchedKeys() {
        var contract = contract();
        Map<String, RuntimePromptContract> map = new LinkedHashMap<>(); map.put(contract.id(), contract);
        var registry = PromptContractRegistry.builder().contracts(map).build(); map.clear();
        assertSame(contract, registry.get(contract.id()));
        assertThrows(IllegalArgumentException.class, () -> registry.get("missing"));
        assertThrows(IllegalArgumentException.class, () -> PromptContractRegistry.builder().contracts(Map.of("wrong", contract)).build());
        assertTrue(PromptContractRegistry.load(new Resource[0]).contracts().isEmpty());
    }

    @Test void explicitPatternsAndScanFailuresAreHandledAtRegistryCreation() {
        var resolver = new PathMatchingResourcePatternResolver() {
            @Override public Resource[] getResources(@NonNull String pattern) {
                assertEquals("classpath*:prompts/runtime/*.prompt.yaml", pattern);
                return new Resource[]{new ClassPathResource(ROOT + FILE)};
            }
        };
        assertEquals(1, PromptContractRegistry.load(resolver, "classpath*:prompts/runtime/*.prompt.yaml").contracts().size());
        var unavailable = new PathMatchingResourcePatternResolver() {
            @Override public Resource[] getResources(@NonNull String pattern) throws IOException { throw new IOException("scan unavailable"); }
        };
        assertInstanceOf(IOException.class, assertThrows(IllegalArgumentException.class, () -> PromptContractRegistry.load(unavailable)).getCause());
    }

    @ParameterizedTest @ValueSource(strings = {"", "null", "[]", "{} {}", "{\"values\":[],\"values\":[]}"})
    void invalidSerializedInputCannotReachTemplateRendering(String serialized) {
        var binding = TestBinding.builder().writer(input -> serialized).reader((json, type) -> fail("Must not read output")).build();
        var failure = assertThrows(IllegalArgumentException.class, () -> PromptRuntime.builder().binding(binding).build().prompt(contract(), Map.of()));
        assertEquals("Prompt input or rendering is invalid", failure.getMessage());
        assertNull(failure.getCause()); assertEquals(0, failure.getSuppressed().length);
    }

    @Test void inputCodecExceptionAndItsCauseAreDiscarded() {
        var binding = TestBinding.builder().writer(input -> {
            var exception = new IOException("synthetic-secret", new IllegalStateException("synthetic-secret"));
            exception.addSuppressed(new IOException("synthetic-secret")); throw exception;
        }).reader((json, type) -> fail("Must not read output")).build();
        var failure = assertThrows(IllegalArgumentException.class,
                () -> PromptRuntime.builder().binding(binding).build().prompt(contract(), Map.of()));
        assertFalse(failure.toString().contains("synthetic-secret"));
        assertNull(failure.getCause()); assertEquals(0, failure.getSuppressed().length);
    }

    @Test void schemaRejectionNeverInvokesBindingInEitherMode() {
        var reads = new AtomicInteger();
        var binding = TestBinding.builder().writer(input -> fail("Must not render input")).reader((json, type) -> {
            reads.incrementAndGet(); return Map.of();
        }).build();
        var runtime = PromptRuntime.builder().binding(binding).build();
        var copy = contract().contract(); ((ObjectNode) copy.document().path("output")).put("strict", true);
        var strict = RuntimePromptContract.builder().contract(copy).build();
        for (var contract : List.of(contract(), strict)) {
            assertFalse(runtime.read(contract, "{", Map.class).accepted());
            assertFalse(runtime.read(contract, "{\"tags\":42}", Map.class).accepted());
        }
        assertFalse(runtime.read(strict, "{}", Map.class).accepted());
        assertFalse(runtime.read(strict, "{\"tags\":[],\"text\":42}", Map.class).accepted());
        assertEquals(0, reads.get());
        assertTrue(runtime.read(strict, "{\"tags\":[]}", Map.class).accepted());
        assertEquals(1, reads.get());
    }

    @ParameterizedTest @ValueSource(strings = {"exception", "null", "wrong-type"})
    void bindingFailuresRetainRepairFindingsWithoutReturningPartialData(String mode) {
        var binding = TestBinding.builder().writer(input -> fail("Must not render input")).reader((json, type) -> {
            var normalized = PromptContract.JSON.readTree(json);
            assertTrue(normalized.path("text").isNull());
            assertEquals(0, normalized.path("tags").size());
            assertFalse(normalized.has("synthetic-secret-key"));
            return switch (mode) {
                case "null" -> null;
                case "wrong-type" -> "synthetic-secret";
                default -> throw new IOException("synthetic-secret");
            };
        }).build();
        var result = PromptRuntime.builder().binding(binding).build().read(contract(),
                "{\"text\":42,\"synthetic-secret-key\":\"synthetic-secret\"}", Map.class);
        assertFalse(result.accepted()); assertNull(result.value());
        assertTrue(result.violations().stream().anyMatch(v -> v.pointer().equals("/text") && v.action() == ResponseViolation.Action.NULLIFIED));
        assertTrue(result.violations().stream().anyMatch(v -> v.pointer().equals("/tags") && v.action() == ResponseViolation.Action.FILLED));
        assertTrue(result.violations().stream().anyMatch(v -> v.pointer().isEmpty() && v.rule().equals("binding") && v.action() == ResponseViolation.Action.REJECTED));
        assertFalse(result.toString().contains("synthetic-secret"));
        assertFalse(result.violations().toString().contains("synthetic-secret"));
    }

    private Path fixture() throws Exception {
        for (String file : List.of(FILE, "rendering.system.st", "rendering.user.st")) {
            try (var input = new ClassPathResource(ROOT + file).getInputStream()) { Files.copy(input, directory.resolve(file)); }
        }
        return directory;
    }

    // Independent callbacks let failures occur at the real serialization/binding boundary, without mocking the validator.
    @Builder
    private record TestBinding(@NonNull Writer writer, @NonNull Reader reader) implements PromptJsonBinding {
        @Override public String write(@NonNull Object input) throws Exception { return writer.write(input); }
        @Override public @Nullable Object read(@NonNull String json, @NonNull Class<?> type) throws Exception { return reader.read(json, type); }
    }
    @FunctionalInterface private interface Writer { String write(Object input) throws Exception; }
    @FunctionalInterface private interface Reader { @Nullable Object read(String json, Class<?> type) throws Exception; }
}
