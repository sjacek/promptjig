package io.github.sjacek.promptjig.maven;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sjacek.promptjig.core.ContractEngine;
import io.github.sjacek.promptjig.core.ValidationRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import lombok.Builder;
import lombok.NonNull;
import lombok.Value;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Adapter behavior uses the shared contract engine and real files, not mocked validation outcomes. */
class PromptjigMojoTest {
    @TempDir Path directory;
    private Fixture fixture;

    @BeforeEach void prepare() throws IOException {
        fixture = Fixture.builder().directory(directory).build();
        fixture.prepare();
    }

    @Test void validationMatchesCoreDiagnosticsAndNeverWritesTheLock() throws Exception {
        byte[] previous = Files.readAllBytes(fixture.lock());
        var mojo = fixture.configure(new ValidateMojo());
        mojo.execute();
        var expected = new ContractEngine().validate(new ValidationRequest(directory, fixture.source(), fixture.lock(), null));
        var report = new ObjectMapper().readTree(directory.resolve("target/reports/validate/validation.json").toFile());
        assertNotNull(report);
        assertTrue(report.path("valid").asBoolean());
        assertEquals(new ObjectMapper().valueToTree(expected.diagnostics()), report.path("diagnostics"));
        assertArrayEquals(previous, Files.readAllBytes(fixture.lock()));
    }

    @Test void aMissingLockFailsValidationWithPortableReportsWithoutCreatingHistory() throws Exception {
        Files.delete(fixture.lock());
        assertThrows(MojoFailureException.class, fixture.configure(new ValidateMojo())::execute);
        assertFalse(Files.exists(fixture.lock()));
        String report = Files.readString(directory.resolve("target/reports/validate/validation.txt"));
        assertTrue(report.contains("[lock]"));
        assertFalse(report.contains(directory.toString()));
    }

    @Test void explicitLockCreatesHistoryAndAnUnchangedRepeatPreservesExactBytes() throws Exception {
        Files.delete(fixture.lock());
        var mojo = fixture.configure(new LockMojo());
        mojo.execute();
        byte[] first = Files.readAllBytes(fixture.lock());
        mojo.execute();
        assertArrayEquals(first, Files.readAllBytes(fixture.lock()));
        fixture.configure(new ValidateMojo()).execute();
    }

    @Test void lockAppendsNewVersionsWithoutRemovingThePreviousFingerprint() throws Exception {
        var mapper = new ObjectMapper();
        var original = mapper.readTree(fixture.lock().toFile());
        assertNotNull(original);
        String first = original.at("/prompts/events.announcement-extraction/1").asText();
        Files.writeString(fixture.contract(), Files.readString(fixture.contract()).replace("version: 1", "version: 2"));
        fixture.configure(new LockMojo()).execute();
        var updated = mapper.readTree(fixture.lock().toFile());
        assertNotNull(updated);
        var history = updated.at("/prompts/events.announcement-extraction");
        assertEquals(first, history.path("1").asText());
        assertFalse(history.path("2").asText().isEmpty());
        fixture.configure(new ValidateMojo()).execute();
    }

    @Test void aChangedVersionCannotBeOverwrittenByTheLockGoal() throws Exception {
        byte[] first = Files.readAllBytes(fixture.lock());
        resource("changed-user.st", fixture.contract().resolveSibling("user.st"));
        assertThrows(MojoFailureException.class, fixture.configure(new LockMojo())::execute);
        assertArrayEquals(first, Files.readAllBytes(fixture.lock()));
        assertTrue(Files.readString(directory.resolve("target/reports/lock/validation.txt")).contains("[lock]"));
    }

    @Test void baselineTamperingIsRejectedEvenWhenTheCurrentContractAndLockAgree() throws Exception {
        Path baseline = directory.resolve("trusted-baseline.json");
        Files.copy(fixture.lock(), baseline);
        Files.delete(fixture.lock());
        resource("changed-user.st", fixture.contract().resolveSibling("user.st"));
        fixture.configure(new LockMojo()).execute();
        byte[] current = Files.readAllBytes(fixture.lock());
        var validate = fixture.configure(new ValidateMojo());
        validate.baselineLockFile = baseline.toFile();
        assertThrows(MojoFailureException.class, validate::execute);
        assertTrue(Files.readString(directory.resolve("target/reports/validate/validation.txt")).contains("[lock-baseline]"));
        var lock = fixture.configure(new LockMojo());
        lock.baselineLockFile = baseline.toFile();
        assertThrows(MojoFailureException.class, lock::execute);
        assertArrayEquals(current, Files.readAllBytes(fixture.lock()));
    }

    @Test void anExplicitMissingBaselineIsNotSilentlySkipped() {
        var mojo = fixture.configure(new ValidateMojo());
        mojo.baselineLockFile = directory.resolve("missing-baseline.json").toFile();
        assertThrows(MojoFailureException.class, mojo::execute);
    }

    @ParameterizedTest @ValueSource(strings = {"v2", "v3"})
    void generationRegistersTypedSourcesAndPreservesAuthoredHistory(@NonNull String jackson) throws Exception {
        byte[] lock = Files.readAllBytes(fixture.lock());
        var mojo = fixture.configure(new GenerateMojo());
        mojo.jackson = jackson;
        String authored = directory.resolve("src/main/java").toString();
        mojo.project.addCompileSourceRoot(authored);
        mojo.execute();
        Path output = directory.resolve("target/generated/example/generated/AnnouncementExtractionOutput.java");
        assertTrue(Files.readString(output).contains("java.time.LocalDate"));
        assertTrue(Files.readString(output).contains("enum Format"));
        assertEquals(List.of(authored, directory.resolve("target/generated").toString()), mojo.project.getCompileSourceRoots());
        assertArrayEquals(lock, Files.readAllBytes(fixture.lock()));
    }

    @Test void anInvalidPackagePreservesExistingOutputAndDoesNotRegisterStaleSources() throws Exception {
        var mojo = fixture.configure(new GenerateMojo());
        Path existing = directory.resolve("target/generated/user-owned.txt");
        Files.createDirectories(existing.getParent());
        Files.writeString(existing, "existing file");
        mojo.packageName = "not-a-java-package";
        assertThrows(MojoFailureException.class, mojo::execute);
        assertEquals("existing file", Files.readString(existing));
        assertTrue(mojo.project.getCompileSourceRoots().isEmpty());
    }

    @Test void generationCannotOverwriteItsInputResources() throws Exception {
        var mojo = fixture.configure(new GenerateMojo());
        mojo.outputDirectory = fixture.source().toFile();
        byte[] contract = Files.readAllBytes(fixture.contract());
        assertThrows(MojoFailureException.class, mojo::execute);
        assertArrayEquals(contract, Files.readAllBytes(fixture.contract()));
        assertTrue(mojo.project.getCompileSourceRoots().isEmpty());
    }

    @Test void templateViolationsAreAggregatedBeforeMavenReportsFailure() throws Exception {
        resource("invalid-parameters.st", fixture.contract().resolveSibling("user.st"));
        assertThrows(MojoFailureException.class, fixture.configure(new ValidateMojo())::execute);
        String text = Files.readString(directory.resolve("target/reports/validate/validation.txt"));
        assertTrue(text.contains("unknownParameter"));
        assertTrue(text.contains("documentText"));
        assertTrue(text.contains("[template-parameters]"));
    }

    @Test void aModuleWithoutContractsDoesNotAcquireAnArtificialLock() throws Exception {
        var mojo = fixture.configure(new ValidateMojo());
        mojo.source = directory.resolve("without-prompts").toFile();
        Files.delete(fixture.lock());
        mojo.execute();
        assertFalse(Files.exists(fixture.lock()));
    }

    @Test void reportIoErrorsAreExecutionFailuresRatherThanContractViolations() throws Exception {
        Path blocked = directory.resolve("not-a-directory");
        Files.writeString(blocked, "synthetic-testdata");
        var mojo = fixture.configure(new ValidateMojo());
        mojo.reportDirectory = blocked.toFile();
        assertThrows(MojoExecutionException.class, mojo::execute);
    }

    @Test void aChangedContractCannotReplacePreviouslyGeneratedSources() throws Exception {
        var mojo = fixture.configure(new GenerateMojo());
        mojo.execute();
        Path generated = directory.resolve("target/generated/example/generated/AnnouncementExtractionOutput.java");
        byte[] output = Files.readAllBytes(generated), history = Files.readAllBytes(fixture.lock());
        resource("changed-user.st", fixture.contract().resolveSibling("user.st"));
        var rejected = fixture.configure(new GenerateMojo());
        assertThrows(MojoFailureException.class, rejected::execute);
        assertArrayEquals(output, Files.readAllBytes(generated));
        assertArrayEquals(history, Files.readAllBytes(fixture.lock()));
        assertTrue(rejected.project.getCompileSourceRoots().isEmpty());
        assertTrue(Files.readString(directory.resolve("target/reports/generate/validation.txt")).contains("[lock]"));
    }

    @Test void generationAlsoEnforcesTheTrustedBaselineBeforePublishingSources() throws Exception {
        Path baseline = directory.resolve("baseline.json");
        Files.copy(fixture.lock(), baseline);
        Files.delete(fixture.lock());
        resource("changed-user.st", fixture.contract().resolveSibling("user.st"));
        fixture.configure(new LockMojo()).execute();
        var mojo = fixture.configure(new GenerateMojo());
        mojo.baselineLockFile = baseline.toFile();
        byte[] history = Files.readAllBytes(fixture.lock());
        assertThrows(MojoFailureException.class, mojo::execute);
        assertFalse(Files.exists(directory.resolve("target/generated")));
        assertArrayEquals(history, Files.readAllBytes(fixture.lock()));
        assertTrue(mojo.project.getCompileSourceRoots().isEmpty());
        assertTrue(Files.readString(directory.resolve("target/reports/generate/validation.txt")).contains("[lock-baseline]"));
    }

    @ParameterizedTest @ValueSource(strings = {"jackson", "resourcePrefix"})
    void invalidGenerationOptionsFailBeforeWritingAnySources(@NonNull String option) throws Exception {
        var mojo = fixture.configure(new GenerateMojo());
        if (option.equals("jackson")) mojo.jackson = "unsupported";
        else mojo.resourcePrefix = "../outside";
        byte[] history = Files.readAllBytes(fixture.lock());
        assertThrows(MojoFailureException.class, mojo::execute);
        assertFalse(Files.exists(directory.resolve("target/generated")));
        assertTrue(mojo.project.getCompileSourceRoots().isEmpty());
        assertArrayEquals(history, Files.readAllBytes(fixture.lock()));
    }

    @Test void aReportWriteFailureDoesNotPublishTheGeneratedSourceRoot() throws Exception {
        Path blocked = directory.resolve("blocked-report");
        Files.writeString(blocked, "synthetic-testdata");
        var mojo = fixture.configure(new GenerateMojo());
        mojo.reportDirectory = blocked.toFile();
        var failure = assertThrows(MojoExecutionException.class, mojo::execute);
        assertInstanceOf(IOException.class, failure.getCause());
        assertTrue(mojo.project.getCompileSourceRoots().isEmpty());
    }

    @Test void separateGoalsKeepTheirOwnReportsWhenExecutedInTheSameModule() throws Exception {
        fixture.configure(new ValidateMojo()).execute();
        Path validation = directory.resolve("target/reports/validate/validation.json");
        byte[] original = Files.readAllBytes(validation);
        fixture.configure(new LockMojo()).execute();
        fixture.configure(new GenerateMojo()).execute();
        assertArrayEquals(original, Files.readAllBytes(validation));
        for (String goal : List.of("validate", "lock", "generate")) {
            assertTrue(Files.isRegularFile(directory.resolve("target/reports/" + goal + "/validation.json")));
            assertTrue(Files.isRegularFile(directory.resolve("target/reports/" + goal + "/validation.txt")));
        }
    }

    @Test void malformedContractsProduceDiagnosticsWithoutMutatingHistory() throws Exception {
        byte[] history = Files.readAllBytes(fixture.lock());
        Files.writeString(fixture.contract(), "promptContract: [\n");
        assertThrows(MojoFailureException.class, fixture.configure(new ValidateMojo())::execute);
        assertArrayEquals(history, Files.readAllBytes(fixture.lock()));
        var report = new ObjectMapper().readTree(directory.resolve("target/reports/validate/validation.json").toFile());
        assertNotNull(report);
        assertFalse(report.path("valid").asBoolean(true));
        assertFalse(report.path("diagnostics").isEmpty());
    }

    private static void resource(@NonNull String name, @NonNull Path output) throws IOException {
        Files.createDirectories(output.getParent());
        try (var input = PromptjigMojoTest.class.getResourceAsStream('/' + name)) {
            if (input == null) throw new IOException("Missing test resource: " + name);
            Files.copy(input, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Each case gets independent files, while the authored public contract stays shared with the runtime pilot. */
    @Value @Builder
    private static class Fixture {
        @NonNull Path directory;

        Path source() { return directory.resolve("src/main/resources/prompts"); }
        Path lock() { return directory.resolve("prompt-contracts.lock.json"); }
        Path contract() { return source().resolve("events/announcement/announcement-extraction.prompt.yaml"); }

        void prepare() throws IOException {
            for (String file : List.of("announcement-extraction.prompt.yaml", "system.st", "user.st"))
                resource("contract-testdata/prompts/events/announcement/" + file, contract().resolveSibling(file));
            resource("contract-testdata/prompt-contracts.lock.json", lock());
        }

        <T extends AbstractPromptjigMojo> T configure(@NonNull T mojo) {
            mojo.projectDirectory = directory.toFile();
            mojo.source = source().toFile();
            mojo.lockFile = lock().toFile();
            mojo.reportDirectory = directory.resolve("target/reports").toFile();
            if (mojo instanceof LockMojo lock) lock.coordinationFile = directory.resolve("target/lock-write.lck").toFile();
            if (mojo instanceof GenerateMojo generate) {
                generate.project = new MavenProject();
                generate.outputDirectory = directory.resolve("target/generated").toFile();
                generate.packageName = "example.generated";
                generate.jackson = "v2";
                generate.resourcePrefix = "prompts";
            }
            return mojo;
        }
    }
}
