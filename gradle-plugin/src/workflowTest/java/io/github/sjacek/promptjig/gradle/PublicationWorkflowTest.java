package io.github.sjacek.promptjig.gradle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import lombok.Builder;
import lombok.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Publication boundaries are tested without Portal keys, network access, real Git mutations or a Gradle upload.
 * YAML comes from the actual workflows; the real Bash guard runs against controlled read-only command doubles.
 * This source set intentionally has no dependency on the plugin's production or TestKit test classpaths.
 */
class PublicationWorkflowTest {
    private static final List<String> PUBLISH_KEYS = List.of("GRADLE_PUBLISH_KEY", "GRADLE_PUBLISH_SECRET");
    @TempDir Path directory;

    private static JsonNode workflow(String name) throws Exception {
        try (var input = PublicationWorkflowTest.class.getResourceAsStream("/workflows/" + name + ".yml")) {
            assertNotNull(input, "Missing tracked workflow resource");
            return new YAMLMapper().readTree(input);
        }
    }

    private static JsonNode step(JsonNode workflow, String id) {
        for (var step : workflow.path("jobs").path("publish").path("steps"))
            if (step.path("id").asText().equals(id)) return step;
        throw new AssertionError("Missing publication phase: " + id);
    }

    @Test void ordinaryCiRunsWithoutPortalCommandsOrPublishingKeys() throws Exception {
        var ci = workflow("compatibility");
        assertTrue(ci.path("on").has("push")); assertTrue(ci.path("on").has("pull_request"));
        for (String key : PUBLISH_KEYS) assertFalse(ci.toString().contains(key), "CI must not depend on publishing keys");
        for (var job : ci.path("jobs")) for (var step : job.path("steps"))
            assertFalse(step.path("run").asText().contains("publishPlugins"), "Even --validate-only requires Portal keys");
        var command = ci.path("jobs").path("runtime-java17").path("steps").toString();
        assertTrue(command.contains(":spring-ai:check")); assertFalse(command.contains(":core:spike"));
    }

    @Test void standaloneRuntimePilotRunsSyntheticChecksAndItsPackagedApplicationWithoutInference() throws Exception {
        var pilot = workflow("compatibility").path("jobs").path("runtime-pilot");
        assertFalse(pilot.isMissingNode());
        assertEquals("${{ !cancelled() }}", pilot.path("if").asText());
        assertEquals("warm-cache", pilot.path("needs").asText());
        assertFalse(pilot.has("continue-on-error"));
        var commands = new StringBuilder();
        boolean java17 = false;
        for (var step : pilot.path("steps")) {
            commands.append(step.path("run").asText()).append('\n');
            if (step.path("uses").asText().startsWith("actions/setup-java@"))
                java17 = step.path("with").path("java-version").asText().equals("17");
        }
        assertTrue(java17);
        assertTrue(commands.toString().contains("-p examples/runtime check installDist"));
        assertTrue(commands.toString().contains("--configuration-cache-problems=fail"));
        assertTrue(commands.toString().contains("$BASE_SHA:examples/runtime/prompt-contracts.lock.json"));
        assertTrue(commands.toString().contains("examples/runtime/build/install/promptjig-runtime-pilot/bin/promptjig-runtime-pilot"));
        assertFalse(commands.toString().contains("runLocal"));
        assertFalse(pilot.toString().contains("PROMPTJIG_MODEL"));
    }

    @Test void consumerMatrixKeepsAllEightSupportedPairsAndDoesNotFailFast() throws Exception {
        var consumer = workflow("compatibility").path("jobs").path("consumer");
        assertFalse(consumer.path("strategy").path("fail-fast").asBoolean(true));
        var matrix = consumer.path("strategy").path("matrix");
        Set<String> pairs = new TreeSet<>();
        for (var gradle : matrix.path("gradle")) for (var java : matrix.path("java")) pairs.add(gradle.asText() + "/" + java.asText());
        for (var excluded : matrix.path("exclude")) pairs.remove(excluded.path("gradle").asText() + "/" + excluded.path("java").asText());
        assertEquals(Set.of("9.0.0/17", "9.0.0/21", "9.1.0/17", "9.1.0/21", "9.1.0/25", "9.9.1/17", "9.9.1/21", "9.9.1/25"), pairs);
    }

    @Test void publicationIsManualAndChecksOutTheRequestedTagWithoutStoredGitCredentials() throws Exception {
        var release = workflow("release");
        assertEquals(Set.of("workflow_dispatch"), fieldNames(release.path("on")));
        assertTrue(release.path("on").path("workflow_dispatch").path("inputs").path("tag").path("required").asBoolean());
        assertEquals("read", release.path("permissions").path("contents").asText());
        assertEquals(Set.of("contents", "actions"), fieldNames(release.path("permissions")));
        assertEquals("read", release.path("permissions").path("actions").asText());
        var publish = release.path("jobs").path("publish");
        assertEquals("plugin-portal", publish.path("environment").asText());
        var checkout = new ArrayList<JsonNode>();
        publish.path("steps").forEach(candidate -> { if (candidate.path("uses").asText().startsWith("actions/checkout@")) checkout.add(candidate); });
        assertEquals(1, checkout.size());
        assertEquals("${{ inputs.tag }}", checkout.get(0).path("with").path("ref").asText());
        assertEquals(0, checkout.get(0).path("with").path("fetch-depth").asInt(-1));
        assertFalse(checkout.get(0).path("with").path("persist-credentials").asBoolean(true));
    }

    @Test void publishingKeysReachOnlyValidationAndUploadAfterGuardAndBuild() throws Exception {
        var release = workflow("release");
        for (String key : PUBLISH_KEYS) {
            assertFalse(release.path("env").has(key)); assertFalse(release.path("jobs").path("publish").path("env").has(key));
        }
        List<String> phases = new ArrayList<>();
        for (var candidate : release.path("jobs").path("publish").path("steps")) {
            var id = candidate.path("id").asText();
            if (!id.isEmpty()) phases.add(id);
            if (Set.of("validate_portal", "publish_portal").contains(id)) {
                for (String key : PUBLISH_KEYS) assertEquals("${{ secrets." + key + " }}", candidate.path("env").path(key).asText());
            } else for (String key : PUBLISH_KEYS) assertFalse(candidate.toString().contains(key));
        }
        assertEquals(List.of("verify_release", "verify_ci", "build", "validate_portal", "publish_portal"), phases);
        assertFalse(step(release, "build").path("run").asText().contains("publishPlugins"));
        assertTrue(step(release, "validate_portal").path("run").asText().contains(":gradle-plugin:publishPlugins --validate-only"));
        assertEquals("./gradlew :gradle-plugin:publishPlugins -PpublicationSboms=true --console=plain", step(release, "publish_portal").path("run").asText());
    }

    @Test void guardBuildAndPortalStepsCannotIgnoreFailuresOrUseUnconditionalUpload() throws Exception {
        var release = workflow("release");
        var jobFailurePolicy = release.path("jobs").path("publish").path("continue-on-error");
        assertTrue(jobFailurePolicy.isMissingNode() || jobFailurePolicy.isBoolean() && !jobFailurePolicy.asBoolean());
        for (String id : List.of("verify_release", "verify_ci", "build", "validate_portal", "publish_portal")) {
            var step = step(release, id);
            assertTrue(step.path("continue-on-error").isMissingNode() || step.path("continue-on-error").isBoolean() && !step.path("continue-on-error").asBoolean());
            assertTrue(Set.of("", "success()", "${{ success() }}").contains(step.path("if").asText()), "Publication must remain success-gated");
        }
        assertEquals("bash", step(release, "verify_release").path("shell").asText());
        assertEquals("${{ inputs.tag }}", step(release, "verify_release").path("env").path("RELEASE_TAG").asText());
    }

    @Test void matchingTagAndCleanSourcesPassAllChecksInOrder() throws Exception {
        var result = guard(Map.of());
        assertEquals(0, result.exitCode(), result.output());
        assertEquals(List.of("gradle", "tag", "head", "worktree", "index"), result.commands());
    }

    @ParameterizedTest
    @ValueSource(strings = {"v9.9.9", "main", "refs/heads/main", "v0.1.0; touch release-injected", "v0.1.0$(touch release-injected)", "v0.1.0\nextra"})
    void wrongBranchVersionAndShellLikeInputStopBeforeGit(String tag) throws Exception {
        var result = guard(Map.of("RELEASE_TAG", tag));
        assertNotEquals(0, result.exitCode());
        assertEquals(List.of("gradle"), result.commands());
        assertFalse(Files.exists(directory.resolve("release-injected")));
    }

    @Test void emptyVersionDoesNotAdmitTagNamedV() throws Exception {
        var result = guard(Map.of("TEST_VERSION", "", "RELEASE_TAG", "v"));
        assertNotEquals(0, result.exitCode()); assertEquals(List.of("gradle"), result.commands());
    }

    @Test void failedVersionCommandStopsBeforeGit() throws Exception {
        var result = guard(Map.of("TEST_GRADLE_EXIT", "3"));
        assertNotEquals(0, result.exitCode()); assertEquals(List.of("gradle"), result.commands());
    }

    @Test void missingTagStopsBeforeReadingHead() throws Exception {
        var result = guard(Map.of("TEST_TAG_EXIT", "1"));
        assertNotEquals(0, result.exitCode()); assertEquals(List.of("gradle", "tag"), result.commands());
    }

    @Test void failedHeadResolutionDoesNotRunCleanlinessChecks() throws Exception {
        var result = guard(Map.of("TEST_HEAD_EXIT", "1"));
        assertNotEquals(0, result.exitCode()); assertEquals(List.of("gradle", "tag", "head"), result.commands());
    }

    @Test void twoFailedResolutionsCannotBeMistakenForMatchingEmptyCommitIds() throws Exception {
        var result = guard(Map.of("TEST_TAG_EXIT", "1", "TEST_HEAD_EXIT", "1"));
        assertNotEquals(0, result.exitCode()); assertEquals(List.of("gradle", "tag"), result.commands());
    }

    @Test void tagPointingElsewhereIsRejectedBeforeCleanlinessChecks() throws Exception {
        var result = guard(Map.of("TEST_TAG_COMMIT", "b".repeat(40)));
        assertNotEquals(0, result.exitCode()); assertEquals(List.of("gradle", "tag", "head"), result.commands());
    }

    @Test void dirtyTrackedSourcesStopBeforeIndexCheck() throws Exception {
        var result = guard(Map.of("TEST_WORKTREE_EXIT", "1"));
        assertNotEquals(0, result.exitCode()); assertEquals(List.of("gradle", "tag", "head", "worktree"), result.commands());
    }

    @Test void stagedChangesAreRejected() throws Exception {
        var result = guard(Map.of("TEST_INDEX_EXIT", "1"));
        assertNotEquals(0, result.exitCode()); assertEquals(List.of("gradle", "tag", "head", "worktree", "index"), result.commands());
    }

    private GuardResult guard(@NonNull Map<String, String> overrides) throws Exception {
        Path bin = Files.createDirectories(directory.resolve("bin"));
        copyExecutable("git", bin.resolve("git")); copyExecutable("gradlew", directory.resolve("gradlew"));
        Path calls = directory.resolve("commands.txt"), output = directory.resolve("output.txt"), script = directory.resolve("guard.sh");
        String actualGuard = step(workflow("release"), "verify_release").path("run").asText();
        // Override command lookup only in this fixture. The production guard is executed verbatim, not reimplemented.
        Files.writeString(script, "export PATH=\"$PWD/bin:$PATH\"\n" + actualGuard, StandardCharsets.UTF_8);
        var builder = new ProcessBuilder(bash(), "--noprofile", "--norc", "guard.sh")
                .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().put("GUARD_CALLS", calls.toString().replace('\\', '/'));
        builder.environment().put("RELEASE_TAG", "v0.1.0"); builder.environment().putAll(overrides);
        PUBLISH_KEYS.forEach(builder.environment()::remove);
        var process = builder.start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("Release guard did not terminate"); }
        return GuardResult.builder().exitCode(process.exitValue()).commands(Files.readAllLines(calls))
                .output(Files.readString(output)).build();
    }

    private static void copyExecutable(String name, Path target) throws Exception {
        try (var input = PublicationWorkflowTest.class.getResourceAsStream("/publication/guard-testdata/" + name)) {
            assertNotNull(input, "Missing executable fixture");
            // Normalize checkout line endings: the production guard and stubs must be portable to Bash on Windows.
            Files.writeString(target, new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n"));
        }
        assertTrue(target.toFile().setExecutable(true));
    }

    private static String bash() {
        // Git for Windows ships Bash independently of any WSL launcher on PATH.
        for (String variable : List.of("ProgramFiles", "ProgramW6432")) {
            String root = System.getenv(variable);
            if (root != null) {
                Path executable = Path.of(root, "Git", "bin", "bash.exe");
                if (Files.isRegularFile(executable)) return executable.toString();
            }
        }
        return "bash";
    }

    private static Set<String> fieldNames(JsonNode object) {
        Set<String> names = new TreeSet<>(); object.fieldNames().forEachRemaining(names::add); return names;
    }

    @Builder
    private record GuardResult(int exitCode, @NonNull List<String> commands, @NonNull String output) { }
}
