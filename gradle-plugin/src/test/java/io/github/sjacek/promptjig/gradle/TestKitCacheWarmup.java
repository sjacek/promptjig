package io.github.sjacek.promptjig.gradle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import lombok.NonNull;

/** Download fixture libraries with each supported Gradle, without applying the candidate or running tests. */
public final class TestKitCacheWarmup {
    private TestKitCacheWarmup() { }

    /** Build task arguments select storage, policy, verified distributions, JDK and the actual host Jackson version. */
    public static void main(@NonNull String[] args) throws IOException {
        Path cache = Path.of(args[0]);
        Path project = Path.of(args[1]);
        Files.createDirectories(project);
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'dependency-warmup-testdata'\n");
        // Functional fixtures use the Java plugin and one external host Jackson dependency. Their generated
        // models use an annotations JAR already resolved by the producer. Jupiter belongs to the producer tests;
        // adding Spock/TestNG here would download frameworks that Promptjig does not exercise.
        Files.writeString(project.resolve("build.gradle"), """
                plugins { id 'java' }
                repositories { mavenCentral() }
                dependencies { implementation 'tools.jackson.core:jackson-databind:%s' }
                tasks.register('warmDependencies') {
                    doLast {
                        configurations.compileClasspath.files.each { println it.name }
                        configurations.runtimeClasspath.files.each { println it.name }
                    }
                }
                """.formatted(args[5]));
        for (String version : Arrays.copyOfRange(args, 6, args.length)) {
            var runner = VerifiedTestKit.create(project, Path.of(args[2])).withTestKitDir(cache.toFile())
                    .withArguments("warmDependencies", "--no-configuration-cache", "--dependency-verification=strict",
                            "--stacktrace", "--console=plain", "-Dorg.gradle.java.home=" + args[4]).forwardOutput();
            if (args[3].isBlank()) runner.withGradleVersion(version);
            else runner.withGradleInstallation(Path.of(args[3], "gradle-" + version).toFile());
            System.out.println("Resolving TestKit dependencies with Gradle " + version);
            runner.build();
        }
    }
}
