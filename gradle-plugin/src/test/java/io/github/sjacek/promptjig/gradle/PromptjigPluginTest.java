package io.github.sjacek.promptjig.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class PromptjigPluginTest {
    @TempDir
    Path directory;

    private Path fixture(String name) throws Exception {
        Path target = directory.resolve(name);
        Path source = Path.of(System.getProperty("promptjig.fixtureDirectory"));
        try (var paths = Files.walk(source)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                Path relative = source.relativize(file);
                if (relative.startsWith("build") || relative.startsWith(".gradle")) continue;
                Path destination = target.resolve(relative);
                Files.createDirectories(destination.getParent());
                Files.copy(file, destination);
            }
        }
        Path build = target.resolve("build.gradle");
        Files.writeString(build, Files.readString(build).replace(" version '0.1.0'", ""));
        Files.writeString(target.resolve("gradle.properties"), "org.gradle.jvmargs=-Xmx384m\norg.gradle.workers.max=2\n");
        return target;
    }

    private GradleRunner runner(Path project, String... tasks) {
        var arguments = new ArrayList<>(Arrays.asList(tasks));
        arguments.addAll(java.util.List.of("--stacktrace", "--console=plain", "--configuration-cache", "--configuration-cache-problems=fail"));
        String javaHome = System.getProperty("promptjig.testJavaHome", "");
        // TestKit's Tooling API does not select its daemon JVM from JAVA_HOME alone.
        if (!javaHome.isBlank()) arguments.add("-Dorg.gradle.java.home=" + javaHome);
        var runner = GradleRunner.create().withProjectDir(project.toFile()).withArguments(arguments)
                .withPluginClasspath().withTestKitDir(new File(System.getProperty("promptjig.testKitDir")));
        if (!javaHome.isBlank()) {
            var environment = new java.util.HashMap<>(System.getenv());
            environment.put("JAVA_HOME", javaHome);
            runner.withEnvironment(environment);
        }
        String version = System.getProperty("promptjig.testGradleVersion", "");
        return version.isBlank() ? runner.withGradleInstallation(new File(System.getProperty("promptjig.testGradleHome")))
                : runner.withGradleVersion(version);
    }

    private Path contract(Path project) {
        return project.resolve("src/main/resources/prompts/patient/disability/certificate-extraction.prompt.yaml");
    }

    private Path template(Path project) {
        return contract(project).resolveSibling("certificate-extraction.user.st");
    }

    private static TaskOutcome outcome(BuildResult result, String path) {
        var task = result.task(path);
        if (task == null) throw new AssertionError("Expected task " + path + " in build result:\n" + result.getOutput());
        return task.getOutcome();
    }

    private void generation(Path project, String jackson) throws Exception {
        String annotations = System.getProperty("promptjig.annotationsJar").replace('\\', '/');
        Files.writeString(project.resolve("build.gradle"), "\npromptContracts { generate { packageName = 'example.generated'; jackson = '"
                + jackson + "' } }\ndependencies { implementation files('" + annotations + "') }\n", StandardOpenOption.APPEND);
    }

    private Path generated(Path project) { return project.resolve("build/generated/sources/prompt-contracts/java/main"); }

    @Test
    void customOutputAndResourcePrefixAreTrackedByCompilationAndConfigurationCache() throws Exception {
        Path project = fixture("custom-generation"); generation(project, "v3");
        Files.writeString(project.resolve("build.gradle"), """

                promptContracts.generate.resourcePrefix = providers.gradleProperty('generatedPrefix').orElse('ai/prompts')
                tasks.named('promptContractsGenerate') { outputDirectory = layout.buildDirectory.dir('custom-generated') }
                tasks.withType(Javadoc).configureEach {
                    options.addBooleanOption('Xdoclint:all', true)
                    options.addBooleanOption('Werror', true)
                }
                """, StandardOpenOption.APPEND);
        runner(project, "promptContractsLock", "compileJava", "javadoc").build();
        Path constants = project.resolve("build/custom-generated/example/generated/CertificateExtractionPromptContract.java");
        assertTrue(Files.readString(constants).contains("\"ai/prompts/patient/disability/certificate-extraction.prompt.yaml\""));
        assertFalse(Files.exists(generated(project)));
        assertTrue(Files.exists(project.resolve("build/classes/java/main/example/generated/CertificateExtractionOutput.class")));
        BuildResult repeated = runner(project, "promptContractsLock", "compileJava", "javadoc").build();
        assertTrue(repeated.getOutput().contains("Reusing configuration cache"));
        assertEquals(TaskOutcome.UP_TO_DATE, outcome(repeated, ":promptContractsGenerate"));
        assertEquals(TaskOutcome.UP_TO_DATE, outcome(repeated, ":javadoc"));
        byte[] lock = Files.readAllBytes(project.resolve("prompt-contracts.lock.json"));
        BuildResult relocatedResource = runner(project, "compileJava", "-PgeneratedPrefix=other/prompts").build();
        assertEquals(TaskOutcome.SUCCESS, outcome(relocatedResource, ":promptContractsGenerate"));
        assertTrue(Files.readString(constants).contains("\"other/prompts/patient/disability/certificate-extraction.prompt.yaml\""));
        assertArrayEquals(lock, Files.readAllBytes(project.resolve("prompt-contracts.lock.json")));
    }

    @Test
    void changedBaselineInvalidatesGenerationAndPreservesPreviousSourcesOnFailure() throws Exception {
        Path project = fixture("generation-baseline"); generation(project, "v2");
        runner(project, "promptContractsLock").build();
        Path baseline = project.resolve("baseline.json");
        Files.copy(project.resolve("prompt-contracts.lock.json"), baseline);
        String property = "-Ppromptjig.baselineLockFile=baseline.json";
        runner(project, "promptContractsGenerate", property).build();
        assertEquals(TaskOutcome.UP_TO_DATE, outcome(runner(project, "promptContractsGenerate", property).build(), ":promptContractsGenerate"));
        Path source = generated(project).resolve("example/generated/CertificateExtractionOutput.java");
        byte[] previous = Files.readAllBytes(source);
        Files.writeString(baseline, Files.readString(baseline).replaceAll("sha256:[0-9a-f]{64}", "sha256:" + "0".repeat(64)));
        BuildResult invalid = runner(project, "promptContractsGenerate", property).buildAndFail();
        assertTrue(invalid.getOutput().contains("Reusing configuration cache"));
        assertEquals(TaskOutcome.FAILED, outcome(invalid, ":promptContractsGenerate"));
        assertTrue(invalid.getOutput().contains("lock-baseline"));
        assertArrayEquals(previous, Files.readAllBytes(source));
        assertTrue(Files.readString(project.resolve("build/reports/prompt-contracts/generate/validation.json"))
                .matches("(?s).*\"valid\"\\s*:\\s*false.*"));
    }

    @Test
    void explicitGenerationWorksWithoutTheJavaPluginAndDoesNotBecomePartOfCheck() throws Exception {
        Path project = fixture("manual-generation");
        Files.writeString(project.resolve("build.gradle"), """
                plugins { id 'io.github.sjacek.promptjig' }
                promptContracts { generate { packageName = 'example.generated'; jackson = 'v3' } }
                """);
        BuildResult generated = runner(project, "promptContractsGenerate").build();
        assertEquals(TaskOutcome.SUCCESS, outcome(generated, ":promptContractsGenerate"));
        assertTrue(Files.exists(generated(project).resolve("example/generated/CertificateExtractionInput.java")));
        BuildResult checked = runner(project, "check").build();
        assertNull(checked.task(":promptContractsGenerate"));
        assertNull(checked.task(":compileJava"));
        assertEquals(TaskOutcome.SUCCESS, outcome(checked, ":promptContractsValidate"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void generationIsWiredIntoCompilationRegardlessOfPluginApplicationOrder(boolean promptjigFirst) throws Exception {
        Path project = fixture("compile-generated");
        if (promptjigFirst) Files.writeString(project.resolve("build.gradle"), "plugins { id 'io.github.sjacek.promptjig'; id 'java' }\n");
        generation(project, promptjigFirst ? "v3" : "v2");
        Path use = project.resolve("src/main/java/example/Use.java"); Files.createDirectories(use.getParent());
        Files.writeString(use, "package example; import example.generated.*; "
                + "@interface Audit { String value(); } @Audit(CertificateExtractionPromptContract.VERSION_REF) "
                + "class Use { String input(CertificateExtractionInput value) { return value.ocrText(); } "
                + "java.time.LocalDate date(CertificateExtractionOutput value) { return value.issuedAt(); } }");
        BuildResult result = runner(project, "promptContractsLock", "compileJava").build();
        assertEquals(TaskOutcome.SUCCESS, outcome(result, ":promptContractsGenerate"));
        assertEquals(TaskOutcome.SUCCESS, outcome(result, ":compileJava"));
        assertTrue(Files.exists(project.resolve("build/classes/java/main/example/Use.class")));
        BuildResult repeated = runner(project, "promptContractsLock", "compileJava").build();
        assertTrue(repeated.getOutput().contains("Reusing configuration cache"));
        assertEquals(TaskOutcome.UP_TO_DATE, outcome(repeated, ":promptContractsGenerate"));
        assertEquals(TaskOutcome.UP_TO_DATE, outcome(repeated, ":compileJava"));
        byte[] source = Files.readAllBytes(generated(project).resolve("example/generated/CertificateExtractionOutput.java"));
        Files.writeString(template(project), "\n", StandardOpenOption.APPEND);
        BuildResult invalid = runner(project, "compileJava").buildAndFail();
        assertEquals(TaskOutcome.FAILED, outcome(invalid, ":promptContractsGenerate"));
        assertNull(invalid.task(":promptContractsLock"));
        assertArrayEquals(source, Files.readAllBytes(generated(project).resolve("example/generated/CertificateExtractionOutput.java")));
    }

    @Test
    void generatedSourcesAndReportsCanBeRestoredFromCacheAfterRelocation() throws Exception {
        Path first = fixture("generate-first"), second = fixture("generate-second");
        generation(first, "v3"); generation(second, "v3");
        String settings = "\nbuildCache { local { directory = file('" + directory.resolve("generation-cache").toString().replace('\\', '/') + "') } }\n";
        for (Path project : java.util.List.of(first, second)) Files.writeString(project.resolve("settings.gradle"), settings, StandardOpenOption.APPEND);
        runner(first, "promptContractsLock").build();
        Files.copy(first.resolve("prompt-contracts.lock.json"), second.resolve("prompt-contracts.lock.json"), StandardCopyOption.REPLACE_EXISTING);
        assertEquals(TaskOutcome.SUCCESS, outcome(runner(first, "promptContractsGenerate", "--build-cache").build(), ":promptContractsGenerate"));
        assertEquals(TaskOutcome.FROM_CACHE, outcome(runner(second, "promptContractsGenerate", "--build-cache").build(), ":promptContractsGenerate"));
        for (String type : java.util.List.of("CertificateExtractionInput", "CertificateExtractionOutput", "CertificateExtractionPromptContract")) {
            Path relative = Path.of("example/generated/" + type + ".java");
            assertArrayEquals(Files.readAllBytes(generated(first).resolve(relative)), Files.readAllBytes(generated(second).resolve(relative)));
        }
        assertEquals(Files.readString(first.resolve("build/reports/prompt-contracts/generate/validation.json")),
                Files.readString(second.resolve("build/reports/prompt-contracts/generate/validation.json")));
    }

    @Test
    void generationRemovesStaleTypesAndDoesNotWireSourcesWhenDisabled() throws Exception {
        Path project = fixture("stale-generated"); generation(project, "v2");
        runner(project, "promptContractsLock", "compileJava").build();
        Files.writeString(project.resolve("build.gradle"), "\npromptContracts.generate.packageName = 'example.renamed'\n", StandardOpenOption.APPEND);
        runner(project, "compileJava").build();
        assertFalse(Files.exists(generated(project).resolve("example/generated/CertificateExtractionOutput.java")));
        assertTrue(Files.exists(generated(project).resolve("example/renamed/CertificateExtractionOutput.java")));
        Files.writeString(project.resolve("build.gradle"), "\npromptContracts.generate.enabled = false\n", StandardOpenOption.APPEND);
        BuildResult disabled = runner(project, "compileJava", "--dry-run").build();
        assertFalse(disabled.getOutput().contains(":promptContractsGenerate SKIPPED"));
        Files.writeString(project.resolve("build.gradle"), "\npromptContracts.generate.enabled = true\n", StandardOpenOption.APPEND);
        Files.delete(contract(project));
        runner(project, "promptContractsGenerate").build();
        assertFalse(Files.exists(generated(project).resolve("example/renamed/CertificateExtractionOutput.java")));
    }

    @Test
    void invalidGenerationSettingsProduceReportsWithoutSources() throws Exception {
        Path project = fixture("bad-generation"); generation(project, "v4");
        runner(project, "promptContractsLock").build();
        BuildResult invalid = runner(project, "promptContractsGenerate").buildAndFail();
        assertTrue(invalid.getOutput().contains("jackson must be v2 or v3"));
        assertFalse(Files.exists(generated(project).resolve("example/generated/CertificateExtractionOutput.java")));
        assertTrue(Files.readString(project.resolve("build/reports/prompt-contracts/generate/validation.json")).contains("\"generate\""));
    }

    @Test
    void checkDetectsChangesAndLockRequiresANewVersionWithConfigurationCache() throws Exception {
        Path project = fixture("consumer");
        Files.deleteIfExists(project.resolve("prompt-contracts.lock.json"));
        BuildResult missing = runner(project, "check").buildAndFail();
        assertEquals(TaskOutcome.FAILED, outcome(missing, ":promptContractsValidate"));
        assertTrue(Files.exists(project.resolve("build/reports/prompt-contracts/validate/validation.json")));
        assertEquals(TaskOutcome.SUCCESS, outcome(runner(project, "promptContractsLock").build(), ":promptContractsLock"));
        Path lock = project.resolve("prompt-contracts.lock.json");
        byte[] history = Files.readAllBytes(lock);
        assertEquals(TaskOutcome.SUCCESS, outcome(runner(project, "check").build(), ":promptContractsValidate"));
        BuildResult reused = runner(project, "check").build();
        assertTrue(reused.getOutput().contains("Reusing configuration cache"));
        assertEquals(TaskOutcome.UP_TO_DATE, outcome(reused, ":promptContractsValidate"));
        Files.writeString(template(project), "\n", StandardOpenOption.APPEND);
        BuildResult changed = runner(project, "check").buildAndFail();
        assertTrue(changed.getOutput().contains("increment version"));
        runner(project, "promptContractsLock").buildAndFail();
        assertArrayEquals(history, Files.readAllBytes(lock));
        Files.writeString(contract(project), Files.readString(contract(project)).replace("version: 1", "version: 2"));
        runner(project, "promptContractsLock").build();
        assertEquals(TaskOutcome.SUCCESS, outcome(runner(project, "check").build(), ":promptContractsValidate"));
        assertTrue(Files.readString(lock).contains("\"1\""));
        assertTrue(Files.readString(lock).contains("\"2\""));
    }

    @Test
    void explicitLockAndCheckCanRunTogetherWithoutMakingLockImplicit() throws Exception {
        Path project = fixture("ordered");
        Files.deleteIfExists(project.resolve("prompt-contracts.lock.json"));
        BuildResult result = runner(project, "promptContractsLock", "check").build();
        assertEquals(TaskOutcome.SUCCESS, outcome(result, ":promptContractsLock"));
        assertEquals(TaskOutcome.SUCCESS, outcome(result, ":promptContractsValidate"));
        assertEquals(TaskOutcome.SUCCESS, outcome(runner(project, "promptContractsLock", "check").build(), ":promptContractsLock"));
    }

    @Test
    void lockAlwaysRunsAndLeavesExistingBytesUntouched() throws Exception {
        Path project = fixture("idempotent");
        runner(project, "promptContractsLock").build();
        byte[] history = Files.readAllBytes(project.resolve("prompt-contracts.lock.json"));
        BuildResult repeated = runner(project, "promptContractsLock").build();
        assertTrue(repeated.getOutput().contains("Reusing configuration cache"));
        assertEquals(TaskOutcome.SUCCESS, outcome(repeated, ":promptContractsLock"));
        assertArrayEquals(history, Files.readAllBytes(project.resolve("prompt-contracts.lock.json")));
    }

    @Test
    void dslOverridesApplyToAlreadyRegisteredTasksAndDoNotCreateTheDefaultLock() throws Exception {
        Path project = fixture("custom-paths");
        Files.move(project.resolve("src/main/resources/prompts"), project.resolve("contracts"));
        Files.deleteIfExists(project.resolve("prompt-contracts.lock.json"));
        Files.writeString(project.resolve("build.gradle"), """

                promptContracts {
                    source = layout.projectDirectory.dir('contracts')
                    lockFile = layout.projectDirectory.file('history/prompts.json')
                }
                """, StandardOpenOption.APPEND);
        BuildResult seeded = runner(project, "promptContractsLock", "check").build();
        assertEquals(TaskOutcome.SUCCESS, outcome(seeded, ":promptContractsValidate"));
        assertTrue(Files.exists(project.resolve("history/prompts.json")));
        assertFalse(Files.exists(project.resolve("prompt-contracts.lock.json")));
        BuildResult repeated = runner(project, "promptContractsLock", "check").build();
        assertTrue(repeated.getOutput().contains("Reusing configuration cache"));
        assertEquals(TaskOutcome.UP_TO_DATE, outcome(repeated, ":promptContractsValidate"));
        byte[] history = Files.readAllBytes(project.resolve("history/prompts.json"));
        Files.writeString(project.resolve("contracts/patient/disability/certificate-extraction.user.st"), "\n", StandardOpenOption.APPEND);
        BuildResult changed = runner(project, "check").buildAndFail();
        assertTrue(changed.getOutput().contains("history/prompts.json"));
        assertNull(changed.task(":promptContractsLock"));
        assertArrayEquals(history, Files.readAllBytes(project.resolve("history/prompts.json")));
    }

    @Test
    void externalBaselineContentInvalidatesValidationWithConfigurationCache() throws Exception {
        Path project = fixture("external-baseline");
        runner(project, "promptContractsLock").build();
        Path lock = project.resolve("prompt-contracts.lock.json");
        Path baseline = directory.resolve("trusted-baseline.json");
        Files.copy(lock, baseline);
        String property = "-Ppromptjig.baselineLockFile=" + baseline;
        runner(project, "check", property).build();
        BuildResult unchanged = runner(project, "check", property).build();
        assertEquals(TaskOutcome.UP_TO_DATE, outcome(unchanged, ":promptContractsValidate"));
        byte[] history = Files.readAllBytes(lock);
        Files.writeString(baseline, Files.readString(baseline).replaceAll("sha256:[0-9a-f]{64}", "sha256:" + "0".repeat(64)));
        BuildResult changed = runner(project, "check", property).buildAndFail();
        assertTrue(changed.getOutput().contains("Reusing configuration cache"));
        assertEquals(TaskOutcome.FAILED, outcome(changed, ":promptContractsValidate"));
        assertTrue(changed.getOutput().contains("lock-baseline"));
        assertArrayEquals(history, Files.readAllBytes(lock));
        Files.delete(baseline);
        runner(project, "check", property).buildAndFail();
        String report = Files.readString(project.resolve("build/reports/prompt-contracts/validate/validation.json"));
        assertTrue(report.contains("baseline/trusted-baseline.json"));
        assertFalse(report.contains(directory.toString().replace("\\", "\\\\")));
    }

    @Test
    void baselineProtectsHistoryAndMissingBaselineIsNotSilentlySkipped() throws Exception {
        Path project = fixture("baseline");
        runner(project, "promptContractsLock").build();
        Path lock = project.resolve("prompt-contracts.lock.json");
        Files.copy(lock, project.resolve("baseline.json"));
        BuildResult valid = runner(project, "check", "-Ppromptjig.baselineLockFile=baseline.json").build();
        assertEquals(TaskOutcome.SUCCESS, outcome(valid, ":promptContractsValidate"));
        Files.writeString(contract(project), Files.readString(contract(project)).replace("version: 1", "version: 2"));
        runner(project, "promptContractsLock").build();
        String history = Files.readString(lock);
        Files.writeString(lock, history.replaceAll("(?m)^\\s*\"1\"[^\\n]*\\n", ""));
        BuildResult changed = runner(project, "check", "-Ppromptjig.baselineLockFile=baseline.json").buildAndFail();
        assertTrue(changed.getOutput().contains("lock-baseline"));
        assertTrue(changed.getOutput().contains("original fingerprint"));
        BuildResult missing = runner(project, "check", "-Ppromptjig.baselineLockFile=missing.json").buildAndFail();
        assertTrue(missing.getOutput().contains("lock-baseline"));
    }

    @Test
    void validationReportsAreRestoredFromBuildCacheAfterRelocation() throws Exception {
        Path first = fixture("first");
        Path second = fixture("second");
        Path cache = directory.resolve("shared-cache");
        String settings = "\nbuildCache { local { directory = file('" + cache.toString().replace('\\', '/') + "') } }\n";
        Files.writeString(first.resolve("settings.gradle"), settings, StandardOpenOption.APPEND);
        Files.writeString(second.resolve("settings.gradle"), settings, StandardOpenOption.APPEND);
        runner(first, "promptContractsLock").build();
        Files.copy(first.resolve("prompt-contracts.lock.json"), second.resolve("prompt-contracts.lock.json"), StandardCopyOption.REPLACE_EXISTING);
        assertEquals(TaskOutcome.SUCCESS, outcome(runner(first, "promptContractsValidate", "--build-cache").build(), ":promptContractsValidate"));
        BuildResult relocated = runner(second, "promptContractsValidate", "--build-cache").build();
        assertEquals(TaskOutcome.FROM_CACHE, outcome(relocated, ":promptContractsValidate"));
        String report = Files.readString(second.resolve("build/reports/prompt-contracts/validate/validation.json"));
        assertFalse(report.contains(first.toString().replace("\\", "\\\\")));
        assertEquals(Files.readString(first.resolve("build/reports/prompt-contracts/validate/validation.json")), report);
    }

    @Test
    void consumerActuallyUsesTheRequestedGradleAndJavaRuntime() throws Exception {
        Path project = fixture("runtime-identity");
        Files.writeString(project.resolve("build.gradle"), """

                tasks.register('runtimeIdentity') {
                    doLast {
                        println('PROMPTJIG_GRADLE=' + org.gradle.util.GradleVersion.current().version)
                        println('PROMPTJIG_JAVA=' + System.getProperty('java.specification.version'))
                    }
                }
                """, StandardOpenOption.APPEND);
        String version = System.getProperty("promptjig.testGradleVersion", "");
        if (version.isBlank()) version = org.gradle.util.GradleVersion.current().getVersion();
        int javaMajor = Runtime.version().feature();
        String javaHome = System.getProperty("promptjig.testJavaHome", "");
        if (!javaHome.isBlank()) {
            var release = new java.util.Properties();
            try (var stream = Files.newInputStream(Path.of(javaHome, "release"))) { release.load(stream); }
            javaMajor = Integer.parseInt(release.getProperty("JAVA_VERSION").replace("\"", "").split("\\.")[0]);
        }
        BuildResult result = runner(project, "promptContractsLock", "check", "runtimeIdentity").build();
        assertTrue(result.getOutput().contains("PROMPTJIG_GRADLE=" + version));
        assertTrue(result.getOutput().contains("PROMPTJIG_JAVA=" + javaMajor), result.getOutput());
    }

    @Test
    void publishedMarkerWorksWithoutInjectedPluginClasspathAndCarriesLicense() throws Exception {
        Path project = fixture("published");
        Files.writeString(project.resolve("settings.gradle"), "pluginManagement { repositories { maven { url = uri('"
                + Path.of(System.getProperty("promptjig.stagingRepository")).toUri() + "') } } }\nrootProject.name = 'published-consumer'\n");
        Files.writeString(project.resolve("build.gradle"), Files.readString(project.resolve("build.gradle"))
                .replace("id 'io.github.sjacek.promptjig'", "id 'io.github.sjacek.promptjig' version '0.1.0'"));
        BuildResult result = runner(project, "promptContractsLock", "check", "--offline").withPluginClasspath(java.util.List.of()).build();
        assertEquals(TaskOutcome.SUCCESS, outcome(result, ":promptContractsValidate"));
        try (var jar = new java.util.jar.JarFile(System.getProperty("promptjig.pluginArtifact"))) {
            assertNotNull(jar.getEntry("META-INF/LICENSE"));
            assertNotNull(jar.getEntry("META-INF/promptjig/runtime.zip"));
        }
    }

    @Test
    void jacksonRuntimeIsNotExposedToTheConsumerBuildscript() throws Exception {
        assertThrows(ClassNotFoundException.class, () -> PromptjigPlugin.class.getClassLoader()
                .loadClass("com.fasterxml.jackson.databind.ObjectMapper"));
        Path project = fixture("host-runtime");
        Path build = project.resolve("build.gradle");
        String host = "buildscript { repositories { mavenCentral() }; dependencies { classpath 'tools.jackson.core:jackson-databind:"
                + System.getProperty("promptjig.jackson3Version") + "' } }\n";
        Files.writeString(build, host + Files.readString(build) + "\ntasks.register('hostRuntime') { doLast { assert "
                + "tools.jackson.databind.json.JsonMapper.builder().build().readTree('{\"host\":true}').path('host').asBoolean() } }\n");
        BuildResult result = runner(project, "promptContractsLock", "check", "hostRuntime").build();
        assertEquals(TaskOutcome.SUCCESS, outcome(result, ":hostRuntime"));
        assertEquals(TaskOutcome.SUCCESS, outcome(result, ":promptContractsValidate"));
    }

    @Test
    void pluginCanBeAppliedWithoutJavaOrPrompts() throws Exception {
        Path project = fixture("empty");
        Files.writeString(project.resolve("build.gradle"), "plugins { id 'io.github.sjacek.promptjig' }\n");
        Path source = contract(project);
        Files.delete(source);
        Files.deleteIfExists(project.resolve("prompt-contracts.lock.json"));
        assertEquals(TaskOutcome.SUCCESS, outcome(runner(project, "check").build(), ":promptContractsValidate"));
    }
}
