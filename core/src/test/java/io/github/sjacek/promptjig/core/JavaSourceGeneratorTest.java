package io.github.sjacek.promptjig.core;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.jspecify.annotations.Nullable;
import javax.tools.ToolProvider;
import java.io.StringWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import static org.junit.jupiter.api.Assertions.*;

class JavaSourceGeneratorTest {
    @TempDir Path directory;
    private final GenerationOptions options = new GenerationOptions("example.generated", "v2");
    private final JavaSourceGenerator generator = new JavaSourceGenerator();

    private ValidationRequest request() { return new ValidationRequest(directory, Path.of("prompts"), Path.of("lock.json"), null); }
    private Path output() { return directory.resolve("build/generated"); }

    private Path fixture() throws Exception {
        Path source = directory.resolve("prompts");
        Files.createDirectories(source);
        var resource = getClass().getResource("/prompts/patient/disability/certificate-extraction.prompt.yaml");
        if (resource == null) throw new AssertionError("Missing certificate contract fixture");
        try (var paths = Files.list(Path.of(resource.toURI()).getParent())) {
            for (Path file : paths.toList()) Files.copy(file, source.resolve(file.getFileName()));
        }
        return source.resolve("certificate-extraction.prompt.yaml");
    }

    private void lock() throws Exception {
        var result = new ContractEngine().updateLock(request(), directory.resolve("build/write.lck"));
        assertTrue(result.valid(), result.diagnostics().toString());
    }

    private void write(Path file, JsonNode document) throws Exception { PromptContract.JSON.writeValue(file.toFile(), document); }

    private Map<String, String> sources() throws Exception {
        Map<String, String> result = new TreeMap<>();
        try (var files = Files.walk(output())) {
            for (Path path : files.filter(Files::isRegularFile).toList()) result.put(output().relativize(path).toString(), Files.readString(path));
        }
        return result;
    }

    private URLClassLoader compile() throws Exception {
        Path classes = directory.resolve("classes"); Files.createDirectories(classes);
        var compiler = ToolProvider.getSystemJavaCompiler(); assertNotNull(compiler);
        try (var manager = compiler.getStandardFileManager(null, null, java.nio.charset.StandardCharsets.UTF_8);
             var files = Files.walk(output())) {
            var sources = files.filter(path -> path.toString().endsWith(".java")).map(Path::toFile).toList();
            Path marker = directory.resolve("AuditMarker.java");
            Files.writeString(marker, "package example.generated; @interface Version { String value(); } "
                    + "@Version(CertificateExtractionPromptContract.VERSION_REF) class AuditMarker { }");
            var all = new java.util.ArrayList<>(sources); all.add(marker.toFile());
            var log = new StringWriter();
            String annotations = Path.of(JsonProperty.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
            assertTrue(compiler.getTask(log, manager, null, List.of("--release", "17", "-encoding", "UTF-8", "-classpath", annotations,
                    "-d", classes.toString()), null, manager.getJavaFileObjectsFromFiles(all)).call(), log.toString());
        }
        return new URLClassLoader(new URL[]{classes.toUri().toURL()}, getClass().getClassLoader());
    }

    @Test
    void generatedPublicApiPassesFullJavadocValidation() throws Exception {
        fixture(); lock();
        assertTrue(generator.generate(request(), options, output()).valid());
        var documentation = ToolProvider.getSystemDocumentationTool();
        assertNotNull(documentation);
        try (var manager = documentation.getStandardFileManager(null, null, java.nio.charset.StandardCharsets.UTF_8);
             var files = Files.walk(output())) {
            var log = new StringWriter();
            String annotations = Path.of(JsonProperty.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
            var sources = manager.getJavaFileObjectsFromFiles(files.filter(path -> path.toString().endsWith(".java")).map(Path::toFile).toList());
            assertTrue(documentation.getTask(log, manager, null, null,
                    List.of("-quiet", "-Xdoclint:all", "-Werror", "-encoding", "UTF-8", "-classpath", annotations,
                            "-d", directory.resolve("javadoc").toString()), sources).call(), log.toString());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"v2", "v3"})
    void inputAndOutputResolveIdenticallyNamedDefinitionsWithinTheirOwnSchema(String jackson) throws Exception {
        Path file = fixture();
        var document = (ObjectNode) PromptContract.load(file).document();
        var input = (ObjectNode) document.path("input").path("schema");
        input.putObject("$defs").putObject("shared").put("type", "string").putArray("enum").add("alpha");
        ((ObjectNode) input.path("properties")).putObject("ocrText").put("$ref", "#/$defs/shared");
        var output = (ObjectNode) document.path("output").path("schema");
        output.remove("required");
        output.putObject("$defs").putObject("shared").put("type", "object").put("additionalProperties", false)
                .putObject("properties").putObject("value").put("type", "string");
        output.putObject("properties").putObject("shared").put("$ref", "#/$defs/shared");
        write(file, document); lock();
        assertTrue(generator.generate(request(), new GenerationOptions(options.packageName(), jackson), output()).valid());
        try (var loader = compile()) {
            Class<?> inputType = loader.loadClass("example.generated.CertificateExtractionInput");
            Object inputValue = read(jackson, "{\"ocrText\":\"alpha\"}", inputType);
            Object enumValue = inputType.getMethod("ocrText").invoke(inputValue);
            assertEquals("alpha", enumValue.getClass().getMethod("value").invoke(enumValue));
            Class<?> outputType = loader.loadClass("example.generated.CertificateExtractionOutput");
            Object outputValue = read(jackson, "{\"shared\":{\"value\":\"output\"}}", outputType);
            Object nested = outputType.getMethod("shared").invoke(outputValue);
            assertEquals("output", nested.getClass().getMethod("value").invoke(nested));
            assertNotEquals(enumValue.getClass(), nested.getClass());
        }
    }

    @Test
    void copiesNullableListItemsWithoutSharingTheCallersMutableList() throws Exception {
        Path file = fixture();
        var document = (ObjectNode) PromptContract.load(file).document();
        var schema = (ObjectNode) document.path("output").path("schema");
        schema.remove("required");
        schema.putObject("properties").putObject("values").put("type", "array")
                .putObject("items").putArray("type").add("string").add("null");
        write(file, document); lock();
        assertTrue(generator.generate(request(), options, output()).valid());
        try (var loader = compile()) {
            var type = loader.loadClass("example.generated.CertificateExtractionOutput");
            var constructor = type.getConstructor(List.class);
            List<@Nullable String> mutable = new java.util.ArrayList<>();
            mutable.add(null);
            mutable.add("one");
            Object value = constructor.newInstance(mutable);
            mutable.clear();
            var snapshot = (List<?>) type.getMethod("values").invoke(value);
            assertEquals(java.util.Arrays.asList(null, "one"), snapshot);
            assertThrows(UnsupportedOperationException.class, snapshot::clear);
            Object empty = constructor.newInstance(new Object[]{null});
            assertEquals(List.of(), type.getMethod("values").invoke(empty));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {254, 255})
    void enforcesTheJvmRecordConstructorSlotLimitBeforeWriting(int fields) throws Exception {
        Path file = fixture();
        var document = (ObjectNode) PromptContract.load(file).document();
        var schema = (ObjectNode) document.path("output").path("schema");
        schema.remove("required");
        var properties = schema.putObject("properties");
        for (int field = 0; field < fields; field++) properties.putObject("field" + field).put("type", "string");
        write(file, document); lock();
        var result = generator.generate(request(), options, output());
        if (fields == 254) {
            assertTrue(result.valid(), result.toString());
            try (var loader = compile()) {
                assertEquals(fields, loader.loadClass("example.generated.CertificateExtractionOutput").getRecordComponents().length);
            }
        } else {
            assertFalse(result.valid());
            assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.message().contains("Too many record components")));
            assertFalse(Files.exists(output()));
        }
    }

    @Test
    void rejectsOversizedUtf8ConstantsWithoutChangingPreviousOutput() throws Exception {
        Path file = fixture(); lock();
        assertTrue(generator.generate(request(), options, output()).valid());
        var previous = sources();
        var document = (ObjectNode) PromptContract.load(file).document();
        document.put("version", 2);
        // Character count fits a constant; modified UTF-8 byte count does not.
        ((ObjectNode) document.path("output").path("schema").path("properties")).putObject("large")
                .put("type", "string").put("pattern", "ą".repeat(35000));
        write(file, document); lock();
        var result = generator.generate(request(), options, output());
        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.message().contains("JVM string constant limit")));
        assertEquals(previous, sources());
    }

    @Test
    void preservesEscapedJsonNamesAndEnumValuesWithoutInjectingJavaOrJavadoc() throws Exception {
        Path file = fixture();
        var document = (ObjectNode) PromptContract.load(file).document();
        var schema = (ObjectNode) document.path("output").path("schema");
        schema.remove("required");
        String property = "odd-\"\\\n*/";
        String wireValue = "quoted-\"\\\n*/-żółć";
        schema.putObject("properties").putObject(property).put("type", "string").putArray("enum").add(wireValue);
        write(file, document); lock();
        assertTrue(generator.generate(request(), options, output()).valid());
        try (var loader = compile()) {
            var type = loader.loadClass("example.generated.CertificateExtractionOutput");
            String json = PromptContract.JSON.createObjectNode().put(property, wireValue).toString();
            Object value = mapper3().readValue(json, type);
            assertEquals(PromptContract.JSON.readTree(json), PromptContract.JSON.readTree(mapper3().writeValueAsString(value)));
        }
    }

    @Test
    void customResourcePrefixAndBaselineFailureAreHonoredWithoutRewritingSources() throws Exception {
        fixture(); lock();
        assertTrue(generator.generate(request(), new GenerationOptions(options.packageName(), "v3", "ai/prompts"), output()).valid());
        try (var loader = compile()) {
            assertEquals("ai/prompts/certificate-extraction.prompt.yaml",
                    loader.loadClass("example.generated.CertificateExtractionPromptContract").getField("RESOURCE").get(null));
        }
        var previous = sources();
        Path baseline = directory.resolve("baseline.json");
        var history = (ObjectNode) PromptContract.JSON.readTree(directory.resolve("lock.json").toFile());
        ((ObjectNode) history.path("prompts").path("patient.disability.certificate-extraction")).put("1", "sha256:" + "0".repeat(64));
        write(baseline, history);
        var result = generator.generate(new ValidationRequest(directory, request().sourceDirectory(), request().lockFile(), baseline),
                options, output());
        assertFalse(result.valid());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.rule().equals("lock-baseline")));
        assertEquals(previous, sources());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "{}", "[42]", "[\"/outside.java\"]", "[\"../outside.java\"]"})
    void malformedOwnershipManifestCannotTriggerWritesOrCleanup(String contents) throws Exception {
        fixture(); lock();
        assertTrue(generator.generate(request(), options, output()).valid());
        Files.writeString(output().resolve(".promptjig-generated.json"), contents);
        var before = sources();
        assertThrows(java.io.IOException.class, () -> generator.generate(request(), options, output()));
        assertEquals(before, sources());
    }

    @ParameterizedTest
    @ValueSource(strings = {"v2", "v3"})
    void generatedRecordsCompileAndRoundTripWithBothJacksonFamilies(String jackson) throws Exception {
        Path file = fixture();
        var document = (ObjectNode) PromptContract.load(file).document().deepCopy();
        try (var schema = getClass().getResourceAsStream("/fixtures/generation-schema.json")) {
            assertNotNull(schema);
            ((ObjectNode) document.path("output")).set("schema", PromptContract.JSON.readTree(schema));
        }
        write(file, document); lock();
        assertTrue(generator.generate(request(), new GenerationOptions(options.packageName(), jackson), output()).valid());
        try (var loader = compile()) {
            Class<?> output = loader.loadClass("example.generated.CertificateExtractionOutput");
            String json = """
                    {"class":"value","getClass":"safe","hyphen-name":"hyphen","string":"in-progress","state":null,
                     "count":123456789012345678901234567890,"amount":1234567890.0123456789,"day":"2026-11-11",
                     "timestamp":"2026-11-11T12:00:00+01:00","integerEnum":2,"numberEnum":1.0,"booleanEnum":true,
                     "codes":["01-U"],"nullableArray":null,"nullableItems":[null,"ok"],
                     "nodes":[{"label":"root","children":[{"label":"leaf"}]}],"empty":{}}
                    """;
            Object value = read(jackson, json, output);
            assertEquals(new java.math.BigInteger("123456789012345678901234567890"), output.getMethod("count").invoke(value));
            assertEquals(new java.math.BigDecimal("1234567890.0123456789"), output.getMethod("amount").invoke(value));
            assertEquals(java.time.LocalDate.of(2026, 11, 11), output.getMethod("day").invoke(value));
            assertEquals(java.time.OffsetDateTime.parse("2026-11-11T12:00:00+01:00").toInstant(),
                    ((java.time.OffsetDateTime) output.getMethod("timestamp").invoke(value)).toInstant());
            assertEquals(List.of(), output.getMethod("nullableArray").invoke(value));
            var nullableItems = (List<?>) output.getMethod("nullableItems").invoke(value);
            assertNull(nullableItems.get(0));
            assertEquals("ok", nullableItems.get(1));
            assertThrows(UnsupportedOperationException.class, nullableItems::clear);
            assertEquals("value", output.getMethod("class_").invoke(value));
            String roundTrip = jackson.equals("v2") ? mapper2().writeValueAsString(value) : mapper3().writeValueAsString(value);
            var tree = PromptContract.JSON.readTree(roundTrip);
            assertEquals("in-progress", tree.path("string").asText());
            assertEquals("01-U", tree.path("codes").get(0).asText());
            assertEquals("hyphen", tree.path("hyphen-name").asText());
            assertTrue(tree.path("state").isNull());
            for (String invalid : List.of("{\"day\":\"2026-02-30\"}", "{\"string\":\"unknown\"}", "{\"codes\":[\"99-X\"]}"))
                assertThrows(Exception.class, () -> read(jackson, invalid, output));
            var constants = loader.loadClass("example.generated.CertificateExtractionPromptContract");
            assertEquals("patient.disability.certificate-extraction-v1", constants.getField("VERSION_REF").get(null));
            assertEquals(PromptContract.load(file).fingerprint(), constants.getField("FINGERPRINT").get(null));
            assertEquals("prompts/certificate-extraction.prompt.yaml", constants.getField("RESOURCE").get(null));
            assertEquals(document.path("output").path("schema"), PromptContract.JSON.readTree((String) constants.getField("OUTPUT_SCHEMA_JSON").get(null)));
        }
    }

    private static com.fasterxml.jackson.databind.ObjectMapper mapper2() {
        return new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private static tools.jackson.databind.json.JsonMapper mapper3() { return tools.jackson.databind.json.JsonMapper.builder().build(); }
    private static Object read(String jackson, String json, Class<?> type) throws Exception {
        return jackson.equals("v2") ? mapper2().readValue(json, type) : mapper3().readValue(json, type);
    }

    @Test
    void generationIsDeterministicAndDoesNotRewriteUnchangedFiles() throws Exception {
        Path file = fixture(); lock();
        assertTrue(generator.generate(request(), options, output()).valid());
        var original = sources();
        Path generated = output().resolve("example/generated/CertificateExtractionOutput.java");
        var modified = Files.getLastModifiedTime(generated);
        assertTrue(generator.generate(request(), options, output()).valid());
        assertEquals(modified, Files.getLastModifiedTime(generated));
        var node = (ObjectNode) PromptContract.load(file).document();
        var reversed = PromptContract.JSON.createObjectNode();
        var properties = new java.util.ArrayList<>(node.properties()); java.util.Collections.reverse(properties);
        properties.forEach(entry -> reversed.set(entry.getKey(), entry.getValue()));
        write(file, reversed);
        assertTrue(generator.generate(request(), options, output()).valid());
        assertEquals(original, sources());
    }

    @Test
    void invalidLockLeavesAllPreviousGeneratedSourcesUntouched() throws Exception {
        Path file = fixture(); lock(); generator.generate(request(), options, output());
        var before = sources();
        Files.writeString(file.resolveSibling("certificate-extraction.user.st"), "\n", StandardOpenOption.APPEND);
        assertFalse(generator.generate(request(), options, output()).valid());
        assertEquals(before, sources());
    }

    @Test
    void removesOnlyOwnedStaleFilesAfterPackageChangeAndContractRemoval() throws Exception {
        Path file = fixture(); lock(); generator.generate(request(), options, output());
        Files.writeString(output().resolve("keep.txt"), "authored");
        assertTrue(generator.generate(request(), new GenerationOptions("example.changed", "v3"), output()).valid());
        assertFalse(Files.exists(output().resolve("example/generated/CertificateExtractionOutput.java")));
        assertTrue(Files.exists(output().resolve("example/changed/CertificateExtractionOutput.java")));
        Files.delete(file);
        assertTrue(generator.generate(request(), options, output()).valid());
        assertEquals(Map.of(".promptjig-generated.json", "[ ]\n", "keep.txt", "authored"), sources());
    }

    @ParameterizedTest
    @ValueSource(strings = {"class.package", "../outside", "example..broken", "example.record"})
    void rejectsInvalidPackagesBeforeCreatingOutput(String name) throws Exception {
        fixture(); lock();
        var result = generator.generate(request(), new GenerationOptions(name, "v2"), output());
        assertFalse(result.valid());
        assertFalse(Files.exists(output()));
    }

    @Test
    void refusesInvalidDialectResourcePrefixAndOutputInsideAuthoredInputs() throws Exception {
        fixture(); lock();
        assertFalse(generator.generate(request(), new GenerationOptions("example", "v4"), output()).valid());
        assertFalse(generator.generate(request(), new GenerationOptions("example", "v2", "../prompts"), output()).valid());
        assertFalse(generator.generate(request(), options, directory.resolve("prompts/generated")).valid());
        assertFalse(generator.generate(request(), options, directory).valid());
        assertFalse(Files.exists(output()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"components", "enum", "top-level", "recursive-array"})
    void reportsUnrepresentableNamesAndTypesWithoutPartialGeneration(String kind) throws Exception {
        Path file = fixture();
        var node = (ObjectNode) PromptContract.load(file).document();
        var schema = (ObjectNode) node.path("output").path("schema");
        var properties = (ObjectNode) schema.path("properties");
        switch (kind) {
            case "components" -> { properties.putObject("a-b").put("type", "string"); properties.putObject("a_b").put("type", "string"); }
            case "enum" -> properties.putObject("collision").put("type", "string").putArray("enum").add("a-b").add("a_b");
            case "top-level" -> { var other = node.deepCopy(); other.put("id", "another.certificate-extraction"); write(file.resolveSibling("other.prompt.yaml"), other); }
            case "recursive-array" -> {
                schema.putObject("$defs").putObject("loop").put("type", "array").putObject("items").put("$ref", "#/$defs/loop");
                properties.putObject("loop").put("$ref", "#/$defs/loop");
            }
            default -> fail(kind);
        }
        write(file, node); lock();
        var result = generator.generate(request(), options, output());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.rule().equals("generate")), result.toString());
        assertFalse(Files.exists(output()));
    }

    @Test
    void neverOverwritesAuthoredFilesOrTrustsTraversalInTheManifest() throws Exception {
        fixture(); lock();
        Path authored = output().resolve("example/generated/CertificateExtractionOutput.java");
        Files.createDirectories(authored.getParent()); Files.writeString(authored, "authored");
        assertThrows(java.io.IOException.class, () -> generator.generate(request(), options, output()));
        assertEquals("authored", Files.readString(authored));
        Files.delete(authored);
        Files.writeString(output().resolve(".promptjig-generated.json"), "[\"../authored.java\"]");
        assertThrows(java.io.IOException.class, () -> generator.generate(request(), options, output()));
        assertFalse(Files.exists(authored));
    }
}
