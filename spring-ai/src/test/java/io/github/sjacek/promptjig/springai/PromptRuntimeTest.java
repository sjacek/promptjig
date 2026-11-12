package io.github.sjacek.promptjig.springai;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.sjacek.promptjig.core.*;
import java.io.StringWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.Message;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.sjacek.promptjig.springai.PromptTestSupport.text;

@NullMarked
class PromptRuntimeTest {
    private static final String ROOT = "prompts/patient/disability/";
    private static final List<String> FILES = List.of("certificate-extraction.prompt.yaml", "certificate-extraction.system.st", "certificate-extraction.user.st");
    @TempDir Path directory;

    private RuntimePromptContract contract() { return PromptContractRegistry.load(new ClassPathResource(ROOT + FILES.get(0)))
            .get("patient.disability.certificate-extraction"); }
    private PromptRuntime runtime(String jackson) { return PromptRuntime.builder()
            .binding(jackson.equals("v2") ? PromptJsonBinding.jackson2() : PromptJsonBinding.jackson3()).build(); }

    // Keep the shared stage-0 fixture and its frozen fingerprint intact; repairs are an explicit caller choice.
    private RuntimePromptContract repairContract() {
        var copy = contract().contract();
        ((ObjectNode) copy.document().path("output")).put("strict", false);
        return RuntimePromptContract.builder().contract(copy).build();
    }

    @Test void rendersOrderedMessagesFromResourcesWithDerivedEnumsAndLiteralOcr() {
        var contract = contract();
        String ocr = "Synthetic Łódź {missing} {contract.output.fields} %s\nsecond line";
        var messages = runtime("v3").prompt(contract, Map.of("ocrText", ocr));
        assertEquals(List.of(MessageType.SYSTEM, MessageType.USER), messages.stream().map(Message::getMessageType).toList());
        assertTrue(text(messages.get(0)).contains("MILD, MODERATE, SEVERE"));
        assertTrue(text(messages.get(0)).contains("01-U, 02-P"));
        // ST4 writes line breaks using the host platform separator, including breaks inside attributes.
        assertTrue(text(messages.get(1)).replace("\r\n", "\n").contains(ocr));
        assertFalse(text(messages.get(0)).contains("{contract"));
        assertEquals(List.of("PERSONAL_DATA", "HEALTH_DATA"), contract.sensitivity());
        assertEquals(contract.id() + "-v1", contract.versionRef());
        assertEquals(contract.contract().outputSchema(), parse(runtime("v3").responseFormat(contract)));
    }

    @ParameterizedTest @ValueSource(strings = {"v2", "v3"})
    void readsActuallyGeneratedModelsWithoutCoercionAndNormalizesLists(String jackson) throws Exception {
        try (var loader = generated(jackson)) {
            Class<?> type = loader.loadClass("testdata.generated.CertificateExtractionOutput");
            Class<?> metadata = loader.loadClass("testdata.generated.CertificateExtractionPromptContract");
            assertEquals(metadata.getField("OUTPUT_SCHEMA_JSON").get(null), runtime(jackson).responseFormat(contract()));
            assertEquals(metadata.getField("VERSION_REF").get(null), contract().versionRef());
            assertEquals(metadata.getField("FINGERPRINT").get(null), contract().fingerprint());
            var result = runtime(jackson).read(repairContract(), """
                    {"patientSurname":"Synthetic", "issuedAt":"2024-02-29", "validTo":"2025-02-30",
                     "certificateType":"PZON_CHILD", "degree":"INVALID", "permanent":"true",
                     "symbolCodes":["01-U","UNKNOWN",42], "extra-secret":"sensitive"}
                    """, type);
            assertTrue(result.accepted(), result.violations().toString());
            var value = result.value();
            assertEquals("Synthetic", type.getMethod("patientSurname").invoke(value));
            assertEquals(LocalDate.of(2024, 2, 29), type.getMethod("issuedAt").invoke(value));
            for (String name : List.of("validTo", "degree", "permanent")) assertNull(type.getMethod(name).invoke(value));
            assertEquals(1, ((List<?>) type.getMethod("symbolCodes").invoke(value)).size());
            assertEquals(List.of(), type.getMethod("indicationCodes").invoke(value));
            assertFalse(result.toString().contains("Synthetic"));
            assertFalse(result.violations().toString().contains("extra-secret"));
            assertFalse(result.violations().toString().contains("sensitive"));
            assertThrows(UnsupportedOperationException.class, () -> result.violations().clear());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"v2", "v3"})
    void rejectsBindingFailureAndStrictContractViolationsWithoutDataInDiagnostics(String jackson) {
        var runtime = runtime(jackson);
        var badType = runtime.read(repairContract(), "{\"patientSurname\":\"secret\"}", String.class);
        assertFalse(badType.accepted());
        assertTrue(badType.violations().stream().anyMatch(v -> v.rule().equals("binding")));
        var copy = contract().contract(); ((ObjectNode) copy.document().path("output")).put("strict", true);
        var strict = RuntimePromptContract.builder().contract(copy).build();
        assertFalse(runtime.read(strict, "{}", Map.class).accepted());
        assertFalse(badType.toString().contains("secret"));
    }

    @Test void rejectsInvalidInputBeforeRenderingAndDoesNotRetainItsCause() {
        for (Object input : List.of(Map.of("ocrText", 42), Map.of(), Map.of("ocrText", "secret", "contract", "secret"))) {
            var failure = assertThrows(IllegalArgumentException.class, () -> runtime("v3").prompt(contract(), input));
            assertNull(failure.getCause()); assertFalse(failure.toString().contains("secret"));
        }
    }

    @Test void registryLoadsJarRelativeTemplatesAndDefaultPatternAndRejectsDuplicateIds() throws Exception {
        Path jar = directory.resolve("prompt-testdata.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (String entry : List.of("prompts/", "prompts/patient/", ROOT)) { output.putNextEntry(new JarEntry(entry)); output.closeEntry(); }
            for (String file : FILES) {
                output.putNextEntry(new JarEntry(ROOT + file));
                try (var input = new ClassPathResource(ROOT + file).getInputStream()) { input.transferTo(output); }
                output.closeEntry();
            }
        }
        try (var loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null)) {
            var resolver = new PathMatchingResourcePatternResolver(loader);
            resolver.setUseCaches(false);
            var registry = PromptContractRegistry.load(resolver);
            assertEquals(1, registry.contracts().size());
            var packaged = registry.get(contract().id());
            assertEquals(contract().fingerprint(), packaged.fingerprint());
            assertTrue(text(runtime("v3").prompt(packaged, Map.of("ocrText", "from jar")).get(1)).contains("from jar"));
            assertThrows(UnsupportedOperationException.class, () -> registry.contracts().clear());
        }
        var resource = new ClassPathResource(ROOT + FILES.get(0));
        assertThrows(IllegalArgumentException.class, () -> PromptContractRegistry.load(resource, resource));
    }

    @Test void startupFailsOnMissingTemplateInvalidContractAndMalformedUtf8() throws Exception {
        Path source = fixture();
        Files.delete(source.resolve(FILES.get(1)));
        assertThrows(IllegalArgumentException.class, () -> PromptContractRegistry.load(new FileSystemResource(source.resolve(FILES.get(0)))));
        Files.write(source.resolve(FILES.get(0)), new byte[]{(byte) 0xc3, (byte) 0x28});
        assertThrows(IllegalArgumentException.class, () -> PromptContractRegistry.load(new FileSystemResource(source.resolve(FILES.get(0)))));
        Files.writeString(source.resolve(FILES.get(0)), "promptContract: 99");
        assertThrows(IllegalArgumentException.class, () -> PromptContractRegistry.load(new FileSystemResource(source.resolve(FILES.get(0)))));
    }

    @Test void concurrentCallsNeverShareTemplateInputOrReadState() throws Exception {
        var contract = repairContract(); var runtime = runtime("v3");
        try (var pool = PromptTestExecutor.builder().threads(4).build()) {
            var calls = java.util.stream.IntStream.range(0, 20).mapToObj(index -> pool.submit(() -> {
                String input = "synthetic-" + index;
                assertTrue(text(runtime.prompt(contract, Map.of("ocrText", input)).get(1)).contains(input));
                assertTrue(runtime.read(contract, "{\"patientSurname\":\"" + input + "\"}", Map.class).accepted());
                return true;
            })).toList();
            for (var call : calls) assertTrue(call.get(10, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test void snapshotCannotBeChangedThroughOriginalOrExposedDocument() {
        var core = contract().contract();
        var snapshot = RuntimePromptContract.builder().contract(core).build();
        var fingerprint = snapshot.fingerprint();
        ((ObjectNode) core.document()).put("version", 99);
        ((ObjectNode) snapshot.contract().document()).put("version", 98);
        assertEquals(1, snapshot.version()); assertEquals(fingerprint, snapshot.fingerprint());
    }

    @ParameterizedTest @ValueSource(strings = {"v2", "v3"})
    void preservesOffsetsAndBigNumbersWhileRejectingNumericAndBooleanStrings(String jackson) throws Exception {
        try (var loader = generated(jackson, true)) {
            var local = PromptContractRegistry.load(new FileSystemResource(directory.resolve("prompts/" + FILES.get(0))))
                    .get("patient.disability.certificate-extraction");
            var copy = local.contract();
            ((ObjectNode) copy.document().path("output")).put("strict", false);
            local = RuntimePromptContract.builder().contract(copy).build();
            Class<?> type = loader.loadClass("testdata.generated.CertificateExtractionOutput");
            var result = runtime(jackson).read(local, """
                    {"timestamp":"2026-11-11T12:30:00+05:30","count":123456789012345678901234567890.0,
                     "amount":1234567890.1234567890123456789,"permanent":true}
                    """, type);
            assertTrue(result.accepted(), result.violations().toString());
            assertEquals(java.time.OffsetDateTime.parse("2026-11-11T12:30:00+05:30"), type.getMethod("timestamp").invoke(result.value()));
            assertEquals(new java.math.BigInteger("123456789012345678901234567890"), type.getMethod("count").invoke(result.value()));
            assertEquals(new java.math.BigDecimal("1234567890.1234567890123456789"), type.getMethod("amount").invoke(result.value()));
            assertEquals(true, type.getMethod("permanent").invoke(result.value()));
            var strings = runtime(jackson).read(local, "{\"count\":\"42\",\"amount\":\"1.5\",\"permanent\":\"true\"}", type);
            assertTrue(strings.accepted());
            for (String field : List.of("count", "amount", "permanent")) {
                assertNull(type.getMethod(field).invoke(strings.value()));
                assertTrue(strings.violations().stream().anyMatch(v -> v.pointer().equals("/" + field) && v.action() == ResponseViolation.Action.NULLIFIED));
            }
        }
    }

    private Path fixture() throws Exception {
        Path source = Files.createDirectories(directory.resolve("prompts"));
        for (String file : FILES) try (var input = new ClassPathResource(ROOT + file).getInputStream()) { Files.copy(input, source.resolve(file)); }
        return source;
    }
    private URLClassLoader generated(String jackson) throws Exception { return generated(jackson, false); }
    private URLClassLoader generated(String jackson, boolean addScalars) throws Exception {
        Path source = fixture();
        if (addScalars) {
            var document = PromptContract.load(source.resolve(FILES.get(0))).document();
            var properties = (ObjectNode) document.path("output").path("schema").path("properties");
            properties.putObject("timestamp").put("format", "date-time").putArray("type").add("string").add("null");
            properties.putObject("count").putArray("type").add("integer").add("null");
            properties.putObject("amount").putArray("type").add("number").add("null");
            PromptContract.JSON.writeValue(source.resolve(FILES.get(0)).toFile(), document);
        }
        var request = new ValidationRequest(directory, Path.of("prompts"), Path.of("lock.json"), null);
        assertTrue(new ContractEngine().updateLock(request, directory.resolve("write.lck")).valid());
        Path generated = directory.resolve("generated");
        assertTrue(new JavaSourceGenerator().generate(request, new GenerationOptions("testdata.generated", jackson), generated).valid());
        Path classes = Files.createDirectories(directory.resolve("classes"));
        var compiler = ToolProvider.getSystemJavaCompiler(); assertNotNull(compiler);
        try (var manager = compiler.getStandardFileManager(null, null, java.nio.charset.StandardCharsets.UTF_8);
             var files = Files.walk(generated)) {
            var log = new StringWriter();
            String annotations = Path.of(JsonProperty.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
            assertTrue(compiler.getTask(log, manager, null, List.of("--release", "17", "-classpath", annotations, "-d", classes.toString()), null,
                    manager.getJavaFileObjectsFromFiles(files.filter(p -> p.toString().endsWith(".java")).map(Path::toFile).toList())).call(), log.toString());
        }
        return new URLClassLoader(new URL[]{classes.toUri().toURL()}, getClass().getClassLoader());
    }
    private static com.fasterxml.jackson.databind.JsonNode parse(String json) {
        try { return PromptContract.JSON.readTree(json); } catch (Exception exception) { throw new AssertionError(exception); }
    }
}
