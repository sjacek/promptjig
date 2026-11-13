package io.github.sjacek.promptjig.gradle;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import lombok.NonNull;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Executes the pilot's real settings offline. Public JAR compatibility belongs to the full runtime pilot;
 * these minimal builds guard the choice between published modules and explicit source substitution. */
class RuntimePilotSettingsTest {
    @TempDir Path directory;

    @Test
    void normalPilotDoesNotEvaluateTheProducerBesideItAndReusesConfigurationCache() throws IOException {
        Path project = fixture();
        // The catalog still belongs to the repository, but the parent must never become an included build.
        Files.writeString(directory.resolve("settings.gradle"), "throw new GradleException('Implicit producer checkout evaluated')");
        Files.writeString(project.resolve("build.gradle"), """
                plugins { id 'base' }
                assert gradle.includedBuilds.empty
                tasks.register('verifySelection') { doLast { println 'PUBLISHED_WITHOUT_CHECKOUT' } }
                """);
        assertTrue(runner(project, "verifySelection").build().getOutput().contains("PUBLISHED_WITHOUT_CHECKOUT"));
        assertTrue(runner(project, "verifySelection").build().getOutput().contains("Reusing configuration cache."));
    }

    @Test
    void explicitPreviewSubstitutesPluginCoreAndRuntimeTogetherWithoutLibraryDownloads() throws IOException {
        Path project = fixture();
        Path preview = Files.createDirectories(directory.resolve("preview"));
        Files.writeString(preview.resolve("settings.gradle"), "rootProject.name = 'explicit-preview'; include 'gradle-plugin', 'core', 'spring-ai'");
        for (String module : List.of("gradle-plugin", "core", "spring-ai")) Files.createDirectories(preview.resolve(module));
        // Deliberately differ from the catalog: all three requests must select projects through substitution.
        Files.writeString(preview.resolve("build.gradle"), """
                allprojects { group = 'io.github.sjacek.promptjig'; version = '99.0.0' }
                subprojects { apply plugin: 'java-library' }
                """);
        Files.writeString(project.resolve("build.gradle"), """
                import org.gradle.api.artifacts.component.ProjectComponentIdentifier
                plugins { id 'java' }
                dependencies {
                    implementation libs.promptjig.runtime
                    implementation "io.github.sjacek.promptjig:promptjig-core:${libs.versions.promptjig.get()}"
                    implementation "io.github.sjacek.promptjig:promptjig-gradle-plugin:${libs.versions.promptjigPlugin.get()}"
                }
                assert gradle.includedBuilds*.projectDir*.canonicalPath == [file('../../preview').canonicalPath]
                def projects = configurations.compileClasspath.incoming.resolutionResult.allComponents*.id
                    .findAll { it instanceof ProjectComponentIdentifier }*.projectPath
                assert projects.containsAll([':gradle-plugin', ':core', ':spring-ai'])
                tasks.register('verifySelection') { doLast { println 'ALL_PREVIEW_MODULES_SELECTED' } }
                """);
        String optIn = "-PpromptjigBuildDir=" + preview;
        assertTrue(runner(project, "verifySelection", optIn).build().getOutput().contains("ALL_PREVIEW_MODULES_SELECTED"));
        assertTrue(runner(project, "verifySelection", optIn).build().getOutput().contains("Reusing configuration cache."));
    }

    private Path fixture() throws IOException {
        Path project = Files.createDirectories(directory.resolve("examples/runtime"));
        resource("settings.gradle", project.resolve("settings.gradle"));
        resource("gradle/libs.versions.toml", directory.resolve("gradle/libs.versions.toml"));
        Files.writeString(project.resolve("gradle.properties"), "org.gradle.jvmargs=-Xmx256m\norg.gradle.workers.max=1\n");
        return project;
    }

    private static void resource(@NonNull String name, @NonNull Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try (var input = RuntimePilotSettingsTest.class.getResourceAsStream("/runtime-settings/" + name)) {
            if (input == null) throw new IOException("Missing authored pilot fixture: " + name);
            Files.copy(input, target);
        }
    }

    private static GradleRunner runner(@NonNull Path project, @NonNull String... tasks) {
        var arguments = new ArrayList<>(Arrays.asList(tasks));
        arguments.addAll(List.of("--offline", "--stacktrace", "--console=plain", "--dependency-verification=strict",
                "--configuration-cache", "--configuration-cache-problems=fail"));
        String javaHome = System.getProperty("promptjig.testJavaHome", "");
        if (!javaHome.isBlank()) arguments.add("-Dorg.gradle.java.home=" + javaHome);
        // No withPluginClasspath(): injecting the producer would hide an accidental source dependency.
        var runner = VerifiedTestKit.create(project, Path.of(System.getProperty("promptjig.verificationMetadata")))
                .withArguments(arguments).withTestKitDir(new File(System.getProperty("promptjig.testKitDir")));
        if (!javaHome.isBlank()) {
            var environment = new HashMap<>(System.getenv());
            environment.put("JAVA_HOME", javaHome);
            runner.withEnvironment(environment);
        }
        String version = System.getProperty("promptjig.testGradleVersion", "");
        if (version.isBlank()) return runner.withGradleInstallation(new File(System.getProperty("promptjig.testGradleHome")));
        String distributions = System.getProperty("promptjig.gradleDistributions", "");
        return distributions.isBlank() ? runner.withGradleVersion(version)
                : runner.withGradleInstallation(Path.of(distributions, "gradle-" + version).toFile());
    }
}
