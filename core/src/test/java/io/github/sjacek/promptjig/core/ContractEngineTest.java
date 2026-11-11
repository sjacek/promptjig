package io.github.sjacek.promptjig.core;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class ContractEngineTest {
    @TempDir
    Path directory;

    private final ContractEngine engine = new ContractEngine();

    private ValidationRequest request() {
        return new ValidationRequest(directory, directory.resolve("prompts"), directory.resolve("prompt-contracts.lock.json"), null);
    }

    private Path fixture(String name) throws Exception {
        Path target = directory.resolve(name);
        Files.createDirectories(target);
        var resource = getClass().getResource("/prompts/patient/disability/certificate-extraction.prompt.yaml");
        if (resource == null) throw new AssertionError("Missing certificate contract fixture");
        try (var files = Files.list(Path.of(resource.toURI()).getParent())) {
            for (Path source : files.toList()) Files.copy(source, target.resolve(source.getFileName()));
        }
        return target.resolve("certificate-extraction.prompt.yaml");
    }

    private ValidationResult update(ValidationRequest request) throws Exception {
        return engine.updateLock(request, directory.resolve("build/lock-write.lck"));
    }

    private ObjectNode document(Path file) {
        return PromptContract.load(file).document().deepCopy();
    }

    private void write(Path file, ObjectNode node) throws Exception {
        PromptContract.JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), node);
    }

    private Set<String> rules(ValidationResult result) {
        return result.diagnostics().stream().filter(diagnostic -> diagnostic.severity() == Diagnostic.Severity.ERROR)
                .map(Diagnostic::rule).collect(Collectors.toSet());
    }

    @Test
    void seedsLockExplicitlyAndNeverMutatesSourcesDuringValidation() throws Exception {
        Path file = fixture("prompts");
        byte[] original = Files.readAllBytes(file);
        assertEquals(Set.of("lock"), rules(engine.validate(request())));
        assertTrue(update(request()).valid());
        byte[] lock = Files.readAllBytes(request().lockFile());
        assertTrue(engine.validate(request()).valid());
        assertTrue(update(request()).valid());
        assertArrayEquals(lock, Files.readAllBytes(request().lockFile()));
        assertArrayEquals(original, Files.readAllBytes(file));
        assertTrue(engine.validate(request()).diagnostics().stream().anyMatch(diagnostic -> diagnostic.rule().equals("lock-baseline")
                && diagnostic.severity() == Diagnostic.Severity.INFO));
    }

    @Test
    void refusesExistingVersionChangesAndAppendsAfterVersionBump() throws Exception {
        Path file = fixture("prompts");
        assertTrue(update(request()).valid());
        byte[] originalLock = Files.readAllBytes(request().lockFile());
        Files.writeString(file.resolveSibling("certificate-extraction.user.st"), "\n", StandardOpenOption.APPEND);
        assertEquals(Set.of("lock"), rules(engine.validate(request())));
        assertFalse(update(request()).valid());
        assertArrayEquals(originalLock, Files.readAllBytes(request().lockFile()));
        var node = document(file);
        node.put("version", 2);
        write(file, node);
        assertFalse(engine.validate(request()).valid());
        assertTrue(update(request()).valid());
        var versions = PromptContract.JSON.readTree(request().lockFile().toFile()).path("prompts")
                .path("patient.disability.certificate-extraction");
        assertTrue(versions.has("1"));
        assertTrue(versions.has("2"));
    }

    @Test
    void baselineCatchesManualRewritesAndHistoricalRemoval() throws Exception {
        Path file = fixture("prompts");
        assertTrue(update(request()).valid());
        Path baseline = directory.resolve("baseline.json");
        Files.copy(request().lockFile(), baseline);
        var protectedRequest = new ValidationRequest(directory, request().sourceDirectory(), request().lockFile(), baseline);
        var lock = (ObjectNode) PromptContract.JSON.readTree(request().lockFile().toFile());
        ((ObjectNode) lock.path("prompts").path("patient.disability.certificate-extraction")).put("1", "sha256:" + "0".repeat(64));
        write(request().lockFile(), lock);
        assertTrue(rules(engine.validate(protectedRequest)).contains("lock-baseline"));
        assertFalse(update(protectedRequest).valid());
        Files.copy(baseline, request().lockFile(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        var node = document(file);
        node.put("version", 2);
        write(file, node);
        assertTrue(update(protectedRequest).valid());
        lock = (ObjectNode) PromptContract.JSON.readTree(request().lockFile().toFile());
        ((ObjectNode) lock.path("prompts").path("patient.disability.certificate-extraction")).remove("1");
        write(request().lockFile(), lock);
        assertEquals(Set.of("lock-baseline"), rules(engine.validate(protectedRequest)));
    }

    @Test
    void missingOrMalformedBaselineIsAnErrorEvenWhenCurrentContractMatches() throws Exception {
        fixture("prompts");
        assertTrue(update(request()).valid());
        Path baseline = directory.resolve("baseline.json");
        var checked = new ValidationRequest(directory, request().sourceDirectory(), request().lockFile(), baseline);
        assertEquals(Set.of("lock-baseline"), rules(engine.validate(checked)));
        Files.writeString(baseline, "{}");
        assertEquals(Set.of("lock-baseline"), rules(engine.validate(checked)));
    }

    @Test
    void malformedLockIsNeverReplaced() throws Exception {
        fixture("prompts");
        for (String text : List.of("not json", "{}", "{\"lockVersion\":4294967297,\"prompts\":{}}",
                "{\"lockVersion\":1,\"prompts\":{\"patient.test\":{\"01\":\"sha256:invalid\"}}}",
                "{\"lockVersion\":1,\"lockVersion\":1,\"prompts\":{}}",
                "{\"lockVersion\":1,\"prompts\":{}} {}",
                "{\"lockVersion\":1,\"prompts\":{},\"extra\":true}")) {
            Files.writeString(request().lockFile(), text);
            assertFalse(update(request()).valid());
            assertEquals(text, Files.readString(request().lockFile()));
        }
    }

    @Test
    void invalidPathConfigurationCannotWriteHistory() throws Exception {
        fixture("prompts");
        for (var invalid : List.of(
                new ValidationRequest(directory, Path.of("../outside-prompts"), Path.of("lock.json"), null),
                new ValidationRequest(directory, Path.of("prompts"), Path.of("../outside.lock.json"), null),
                new ValidationRequest(directory, Path.of("prompts"), Path.of("prompts/lock.json"), null))) {
            var result = update(invalid);
            assertEquals(Set.of("contract-schema"), rules(result));
            assertEquals(0, result.contracts());
        }
        assertFalse(Files.exists(directory.resolve("lock.json")));
        assertFalse(Files.exists(directory.resolve("prompts/lock.json")));
    }

    @Test
    void sourceMustBeADirectoryEvenIfItContainsValidYaml() throws Exception {
        Path file = fixture("prompts");
        var invalid = new ValidationRequest(directory, file, request().lockFile(), null);
        assertEquals(Set.of("contract-schema"), rules(update(invalid)));
        assertFalse(Files.exists(request().lockFile()));
    }

    @Test
    void relativePathsAppendVersionsInNumericOrderAndRetainHistoryAfterContractRemoval() throws Exception {
        Path file = fixture("prompts");
        var input = new ValidationRequest(directory, Path.of("prompts"), Path.of("history/lock.json"), null);
        assertTrue(update(input).valid());
        for (int version : List.of(10, 2)) {
            var node = document(file);
            node.put("version", version);
            write(file, node);
            assertTrue(update(input).valid());
        }
        Path lock = directory.resolve(input.lockFile());
        String history = Files.readString(lock);
        var versions = PromptContract.JSON.readTree(history).path("prompts").path("patient.disability.certificate-extraction");
        var names = new java.util.ArrayList<String>();
        versions.fieldNames().forEachRemaining(names::add);
        assertEquals(List.of("1", "2", "10"), names);
        Files.delete(file);
        assertTrue(update(input).valid());
        assertEquals(history, Files.readString(lock));
    }

    @Test
    void malformedUtf8TemplatesFailWithoutPartialHistoryOrLeakingContents() throws Exception {
        Path file = fixture("prompts");
        assertTrue(update(request()).valid());
        byte[] history = Files.readAllBytes(request().lockFile());
        Files.write(file.resolveSibling("certificate-extraction.user.st"), new byte[]{(byte) 0xc3, 0x28});
        var result = update(request());
        assertTrue(rules(result).contains("template-exists"));
        assertEquals(1, result.contracts());
        assertArrayEquals(history, Files.readAllBytes(request().lockFile()));
        assertTrue(result.diagnostics().stream().noneMatch(diagnostic -> diagnostic.message().contains(directory.toString())));
    }

    @Test
    void externalBaselineUsesAPortableLabelAndCannotBeChangedByUpdate() throws Exception {
        Path file = fixture("project/prompts");
        Path project = file.getParent().getParent();
        Path lock = project.resolve("history.json");
        var input = new ValidationRequest(project, Path.of("prompts"), lock, null);
        assertTrue(update(input).valid());
        Path baseline = directory.resolve("trusted-history.json");
        Files.copy(lock, baseline);
        byte[] trusted = Files.readAllBytes(baseline);
        Files.writeString(lock, "{\"lockVersion\":1,\"prompts\":{}}");
        var result = update(new ValidationRequest(project, Path.of("prompts"), lock, baseline));
        assertEquals(Set.of("lock-baseline"), rules(result));
        assertArrayEquals(trusted, Files.readAllBytes(baseline));
        assertEquals("{\"lockVersion\":1,\"prompts\":{}}", Files.readString(lock));
        assertTrue(result.diagnostics().stream().noneMatch(diagnostic -> Path.of(diagnostic.file()).isAbsolute()));
        Files.move(baseline, directory.resolve("retained-history.json"));
        var missing = engine.validate(new ValidationRequest(project, Path.of("prompts"), lock, baseline));
        assertTrue(missing.diagnostics().stream().anyMatch(diagnostic -> diagnostic.rule().equals("lock-baseline")
                && diagnostic.file().equals("baseline/trusted-history.json")));
    }

    @Test
    void aggregatesLocatedDiagnosticsAcrossFilesAndNeverWritesPartialLock() throws Exception {
        Path file = fixture("prompts");
        assertTrue(update(request()).valid());
        byte[] lock = Files.readAllBytes(request().lockFile());
        var node = document(file);
        node.put("id", "patient.missing-template");
        ((ObjectNode) node.path("messages").get(0)).put("template", "missing.st");
        write(file.resolveSibling("missing.prompt.yaml"), node);
        node = document(file);
        node.put("model", "forbidden");
        write(file.resolveSibling("bad.prompt.yaml"), node);
        node = document(file);
        write(file.resolveSibling("duplicate.prompt.yaml"), node);
        var result = update(request());
        assertTrue(rules(result).containsAll(Set.of("template-exists", "contract-schema", "unique-id")));
        assertTrue(result.diagnostics().stream().noneMatch(diagnostic -> Path.of(diagnostic.file()).isAbsolute()));
        assertTrue(result.diagnostics().stream().filter(diagnostic -> diagnostic.severity() == Diagnostic.Severity.ERROR)
                .anyMatch(diagnostic -> diagnostic.line() > 1 && diagnostic.column() > 1));
        assertArrayEquals(lock, Files.readAllBytes(request().lockFile()));
        result.writeReports(directory.resolve("reports"));
        assertFalse(Files.readString(directory.resolve("reports/validation.json")).contains(directory.toString().replace("\\", "\\\\")));
    }

    @Test
    void detectsUnknownAndUnusedParametersWithTemplateCoordinates() throws Exception {
        Path file = fixture("prompts");
        var node = document(file);
        ((ObjectNode) node.path("input").path("schema").path("properties")).remove("ocrText");
        write(file, node);
        var result = update(request());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.rule().equals("template-parameters")
                && diagnostic.file().endsWith("user.st") && diagnostic.line() == 2 && diagnostic.column() == 2));
        assertFalse(Files.exists(request().lockFile()));
    }

    @Test
    void rejectsInvalidSemanticSchemasBeforeWritingAnyFingerprint() throws Exception {
        Path file = fixture("prompts");
        var original = document(file);
        for (String invalid : List.of(
                "{\"type\":\"object\",\"properties\":{}}",
                "{\"type\":\"array\"}",
                "{\"type\":\"string\",\"pattern\":\"[\"}",
                "{\"type\":\"string\",\"enum\":[true]}",
                "{\"type\":\"integer\",\"format\":\"date\"}",
                "{\"$ref\":\"#/$defs/missing\"}")) {
            ObjectNode node = original.deepCopy();
            ((ObjectNode) node.path("output").path("schema").path("properties")).set("invalid", PromptContract.JSON.readTree(invalid));
            write(file, node);
            assertTrue(rules(update(request())).contains("schema-subset"), invalid);
        }
        assertFalse(Files.exists(request().lockFile()));
    }

    @Test
    void requiredMustNameAnExistingProperty() throws Exception {
        Path file = fixture("prompts");
        var node = document(file);
        ((ObjectNode) node.path("output").path("schema")).putArray("required").add("undeclared");
        write(file, node);
        assertEquals(Set.of("schema-subset"), rules(update(request())));
    }

    @Test
    void rejectsReferenceAliasCyclesButAllowsConcreteRecursiveDefinitions() throws Exception {
        Path file = fixture("prompts");
        var node = document(file);
        var output = (ObjectNode) node.path("output").path("schema");
        output.putObject("$defs").putObject("cycle").put("$ref", "#/$defs/cycle");
        write(file, node);
        assertTrue(rules(update(request())).contains("schema-subset"));
        var definition = ((ObjectNode) output.path("$defs")).putObject("cycle");
        definition.put("type", "object").put("additionalProperties", false);
        definition.putObject("properties").putObject("child").put("$ref", "#/$defs/cycle");
        write(file, node);
        assertTrue(update(request()).valid());
    }

    @Test
    void lineEndingChangesDoNotRequireANewVersion() throws Exception {
        Path file = fixture("prompts");
        assertTrue(update(request()).valid());
        Path template = file.resolveSibling("certificate-extraction.user.st");
        Files.writeString(template, Files.readString(template).replace("\n", "\r\n"));
        assertTrue(engine.validate(request()).valid());
    }

    @Test
    void noContractsDoesNotRequireALockButCannotEraseBaselineHistory() throws Exception {
        assertTrue(engine.validate(request()).valid());
        fixture("prompts");
        assertTrue(update(request()).valid());
        Path baseline = directory.resolve("baseline.json");
        Files.move(request().lockFile(), baseline);
        Files.delete(directory.resolve("prompts/certificate-extraction.prompt.yaml"));
        var checked = new ValidationRequest(directory, request().sourceDirectory(), request().lockFile(), baseline);
        assertTrue(rules(engine.validate(checked)).contains("lock"));
        assertTrue(rules(update(checked)).contains("lock-baseline"));
    }

    @Test
    void requiresExactTemplateCaseOnEveryFilesystem() throws Exception {
        Path file = fixture("prompts");
        var node = document(file);
        ((ObjectNode) node.path("messages").get(0)).put("template", "Certificate-extraction.system.st");
        write(file, node);
        assertTrue(rules(update(request())).contains("template-exists"));
    }

    @Test
    void duplicateIdOrderingIsCaseSensitiveAndPortable() throws Exception {
        Path file = fixture("prompts");
        Files.copy(file, file.resolveSibling("a.prompt.yaml"));
        Files.copy(file, file.resolveSibling("B.prompt.yaml"));
        var duplicates = update(request()).diagnostics().stream().filter(diagnostic -> diagnostic.rule().equals("unique-id")).toList();
        assertEquals(2, duplicates.size());
        assertTrue(duplicates.stream().allMatch(diagnostic -> diagnostic.message().endsWith("prompts/B.prompt.yaml")));
    }

    @Test
    void yamlAliasesAndMultipleDocumentsRetainTheirActualLocations() throws Exception {
        Path file = fixture("prompts");
        String original = Files.readString(file);
        Files.writeString(file, original + "\n---\nid: another.document\n");
        assertTrue(update(request()).diagnostics().stream().anyMatch(diagnostic -> diagnostic.rule().equals("contract-schema")
                && diagnostic.line() > 1 && diagnostic.message().contains("exactly one")));
        Files.writeString(file, original + "\nextra: &anchor value\nalias: *anchor\n");
        assertTrue(update(request()).diagnostics().stream().anyMatch(diagnostic -> diagnostic.rule().equals("contract-schema")
                && diagnostic.line() > 1 && diagnostic.message().contains("aliases")));
    }

    @Test
    void independentlyGeneratedReportsAreIdenticalAfterRelocation() throws Exception {
        Path first = fixture("first/prompts"), second = fixture("second/prompts");
        Files.delete(first.resolveSibling("certificate-extraction.user.st"));
        Files.delete(second.resolveSibling("certificate-extraction.user.st"));
        var firstResult = engine.validate(new ValidationRequest(first.getParent().getParent(), first.getParent(),
                first.getParent().getParent().resolve("prompt-contracts.lock.json"), null));
        var secondResult = engine.validate(new ValidationRequest(second.getParent().getParent(), second.getParent(),
                second.getParent().getParent().resolve("prompt-contracts.lock.json"), null));
        Path firstReport = directory.resolve("first-report"), secondReport = directory.resolve("second-report");
        firstResult.writeReports(firstReport);
        secondResult.writeReports(secondReport);
        assertArrayEquals(Files.readAllBytes(firstReport.resolve("validation.json")), Files.readAllBytes(secondReport.resolve("validation.json")));
        assertArrayEquals(Files.readAllBytes(firstReport.resolve("validation.txt")), Files.readAllBytes(secondReport.resolve("validation.txt")));
    }

    @Test
    void concurrentAppendsPreserveBothPromptHistories() throws Exception {
        Path first = fixture("one"), second = fixture("two");
        var node = document(first); node.put("id", "patient.first"); write(first, node);
        node = document(second); node.put("id", "patient.second"); write(second, node);
        var requests = List.of(new ValidationRequest(directory, first.getParent(), request().lockFile(), null),
                new ValidationRequest(directory, second.getParent(), request().lockFile(), null));
        // The shared pool owns its threads; this test creates no executor that would need Java 21's close().
        var futures = requests.stream().map(input -> CompletableFuture.supplyAsync(() -> {
            try { return update(input); }
            catch (Exception exception) { throw new CompletionException(exception); }
        })).toList();
        for (var future : futures) assertTrue(future.get(30, TimeUnit.SECONDS).valid());
        assertEquals(2, PromptContract.JSON.readTree(request().lockFile().toFile()).path("prompts").size());
    }
}
