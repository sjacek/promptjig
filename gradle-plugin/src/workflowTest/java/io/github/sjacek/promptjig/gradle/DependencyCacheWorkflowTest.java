package io.github.sjacek.promptjig.gradle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** The real workflow must fail required checks on preparation failure, including every matrix cell. */
class DependencyCacheWorkflowTest {
    @TempDir Path directory;

    private static JsonNode workflow() throws Exception {
        try (var input = DependencyCacheWorkflowTest.class.getResourceAsStream("/workflows/compatibility.yml")) {
            assertNotNull(input);
            return new YAMLMapper().readTree(input);
        }
    }

    @Test void onlyTheWarmupJobCanSaveAndOnlyOnTheCanonicalDefaultBranch() throws Exception {
        var workflow = workflow();
        assertEquals("read", workflow.path("permissions").path("contents").asText());
        int saves = 0;
        for (var job : workflow.path("jobs").properties()) {
            for (var step : job.getValue().path("steps")) {
                String action = step.path("uses").asText();
                if (action.startsWith("actions/cache/save@")) {
                    saves++;
                    assertEquals("warm-cache", job.getKey());
                    String condition = step.path("if").asText();
                    for (String boundary : List.of("github.event_name == 'push'", "github.repository == 'sjacek/promptjig'",
                            "github.event.repository.default_branch", "cache-hit != 'true'")) assertTrue(condition.contains(boundary));
                }
                if (action.startsWith("gradle/actions/setup-gradle@"))
                    assertTrue(step.path("with").path("cache-disabled").asBoolean());
                assertFalse(step.path("continue-on-error").asBoolean());
                assertFalse(step.path("run").asText().contains("--write-verification-metadata"));
            }
        }
        assertEquals(1, saves);
    }

    @Test void cacheMissAndExactHitsBothResolveBeforePublishingSameRunArtifact() throws Exception {
        var warm = workflow().path("jobs").path("warm-cache");
        int resolve = -1, pack = -1, upload = -1, index = 0;
        boolean compatibleFallback = false;
        for (var step : warm.path("steps")) {
            String command = step.path("run").asText();
            if (command.equals("python3 -B scripts/ci_dependency_cache.py warm")) {
                resolve = index;
                assertFalse(step.has("if"), "Even exact hits must fill missing downloads");
            }
            if (command.endsWith("ci_dependency_cache.py pack")) pack = index;
            if (step.path("uses").asText().startsWith("actions/upload-artifact@")
                    && step.path("with").path("name").asText().equals("ci-dependencies")) {
                upload = index;
                assertEquals("ci-dependencies", step.path("with").path("name").asText());
                assertEquals("error", step.path("with").path("if-no-files-found").asText());
                assertFalse(step.has("if"), "Failed preparation must not publish an apparently usable artifact");
            }
            if (step.path("uses").asText().startsWith("actions/cache/restore@")) {
                String key = step.path("with").path("key").asText();
                for (String input : List.of("runner.os", "runner.arch", "libs.versions.toml", "verification-metadata.xml",
                        "gradle-wrapper.properties", "ci-dependencies.json", "ci_dependency", ".github/actions", "scripts/maven_*.py", "maven-plugin/src/it/**", "examples/maven/**"))
                    assertTrue(key.contains(input), input);
                compatibleFallback = step.path("with").has("restore-keys");
            }
            index++;
        }
        assertTrue(compatibleFallback);
        assertTrue(resolve >= 0 && pack > resolve && upload > pack);
    }

    @Test void preparationFailuresPublishOnlyDiagnosticReportsAsADistinctArtifact() throws Exception {
        var steps = workflow().path("jobs").path("warm-cache").path("steps");
        int diagnosticUploads = 0;
        for (var step : steps) {
            if (!step.path("uses").asText().startsWith("actions/upload-artifact@")) continue;
            String name = step.path("with").path("name").asText();
            if (name.equals("ci-dependencies")) {
                assertFalse(step.has("if"), "A failed job must not publish a usable snapshot");
                continue;
            }
            diagnosticUploads++;
            assertEquals("dependency-preparation-diagnostics", name);
            assertEquals("failure()", step.path("if").asText());
            assertEquals(List.of("build/reports/dependency-verification/", "build/reports/problems/",
                    "gradle-plugin/build/cache-warmup/testkit/build/reports/dependency-verification/",
                    "examples/*/build/reports/dependency-verification/"),
                    step.path("with").path("path").asText().lines().toList());
            assertFalse(step.path("continue-on-error").asBoolean());
        }
        assertEquals(1, diagnosticUploads);
    }

    @Test void everyConsumerRequiresWarmupAndRunsFailureGuardBeforeCheckingOutCode() throws Exception {
        var jobs = workflow().path("jobs");
        for (String name : List.of("runtime-java17", "core-and-packaging", "runtime-pilot", "consumer", "maven", "dependency-security", "offline-security")) {
            var job = jobs.path(name);
            assertEquals("warm-cache", job.path("needs").asText());
            assertEquals("${{ !cancelled() }}", job.path("if").asText());
            var guard = job.path("steps").path(0);
            assertEquals("${{ needs.warm-cache.result }}", guard.path("env").path("DEPENDENCIES_RESULT").asText());
            assertTrue(guard.path("run").asText().contains("exit 1"));
            boolean restore = false;
            for (var step : job.path("steps"))
                if (step.path("uses").asText().equals("./.github/actions/restore-dependencies")) restore = true;
            assertTrue(restore, name);
        }
    }

    @Test void nativeMavenMatrixExecutesAllSixRequiredPairsAgainstCurrentCandidatesOffline() throws Exception {
        var job = workflow().path("jobs").path("maven");
        var matrix = job.path("strategy").path("matrix");
        assertEquals(List.of("3.9.16", "4.0.0-rc-7"),
                java.util.stream.StreamSupport.stream(matrix.path("maven").spliterator(), false).map(JsonNode::asText).toList());
        assertEquals(List.of("17", "21", "25"),
                java.util.stream.StreamSupport.stream(matrix.path("java").spliterator(), false).map(JsonNode::asText).toList());
        assertFalse(matrix.has("exclude"));
        assertFalse(job.path("strategy").path("fail-fast").asBoolean(true));
        var commands = new StringBuilder();
        for (var step : job.path("steps")) {
            commands.append(step.path("run").asText()).append('\n');
            assertFalse(step.path("continue-on-error").asBoolean());
        }
        for (String required : List.of(":core:publishAllPublicationsToStagingRepository",
                ":maven-plugin:publishAllPublicationsToStagingRepository", "--offline", "--dependency-verification=strict",
                "--configuration-cache-problems=fail", "maven_integration.py verify", "--java-home \"$CONSUMER_JAVA_HOME\""))
            assertTrue(commands.toString().contains(required), required);
        assertFalse(commands.toString().contains("skipTests"));
    }

    @Test void actualGuardRejectsFailureCancellationAndSkippedPreparation() throws Exception {
        String guard = workflow().path("jobs").path("consumer").path("steps").path(0).path("run").asText();
        String bash = "bash";
        String programFiles = System.getenv("ProgramFiles");
        if (programFiles != null && Files.isRegularFile(Path.of(programFiles, "Git/bin/bash.exe")))
            bash = Path.of(programFiles, "Git/bin/bash.exe").toString();
        for (String status : List.of("success", "failure", "cancelled", "skipped", "")) {
            Path output = directory.resolve("guard-" + status + ".txt");
            var builder = new ProcessBuilder(bash, "--noprofile", "--norc", "-c", guard)
                    .redirectErrorStream(true).redirectOutput(output.toFile());
            builder.environment().put("DEPENDENCIES_RESULT", status);
            var process = builder.start();
            if (!process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("Prerequisite guard hung"); }
            assertEquals(status.equals("success"), process.exitValue() == 0, Files.readString(output));
        }
    }
}
