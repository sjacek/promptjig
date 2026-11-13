package io.github.sjacek.promptjig.gradle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import lombok.NonNull;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Keep signing keys, Central tokens, tested commit identity and recovery paths independent. */
class CentralPublicationWorkflowTest {
    private static final Set<String> SIGNING = Set.of("MAVEN_GPG_KEY", "MAVEN_GPG_PASSPHRASE");
    private static final Set<String> CENTRAL = Set.of("MAVEN_CENTRAL_USERNAME", "MAVEN_CENTRAL_PASSWORD");

    private static JsonNode workflow(@NonNull String name) throws Exception {
        try (var input = CentralPublicationWorkflowTest.class.getResourceAsStream("/workflows/" + name + ".yml")) {
            assertNotNull(input);
            return new YAMLMapper().readTree(input);
        }
    }

    private static JsonNode step(@NonNull JsonNode job, @NonNull String id) {
        for (var step : job.path("steps")) if (id.equals(step.path("id").asText())) return step;
        throw new AssertionError("Missing release phase: " + id);
    }

    @Test void centralPublicationIsManualCanonicalAndSerializedWithoutCancellation() throws Exception {
        var document = workflow("release-central");
        assertEquals(Set.of("workflow_dispatch"), fields(document.path("on")));
        assertTrue(document.path("on").path("workflow_dispatch").path("inputs").path("tag").path("required").asBoolean());
        assertFalse(document.path("concurrency").path("cancel-in-progress").asBoolean(true));
        assertEquals("central-${{ inputs.tag }}", document.path("concurrency").path("group").asText());
        assertEquals("github.repository == 'sjacek/promptjig'", document.path("jobs").path("verify").path("if").asText());
        assertEquals(Set.of("contents", "actions"), fields(document.path("permissions")));
        document.path("permissions").forEach(value -> assertEquals("read", value.asText()));
    }

    @Test void centralGuardExecutesTheSameAlreadyTestedTagChecksBeforePublishingItsSha() throws Exception {
        var verify = workflow("release-central").path("jobs").path("verify");
        String portal = commands(step(workflow("release").path("jobs").path("publish"), "verify_release"));
        String central = commands(step(verify, "verify_release"));
        assertEquals(portal + "\necho \"commit=$head_commit\" >> \"$GITHUB_OUTPUT\"", central);
        assertEquals("${{ inputs.tag }}", step(verify, "verify_release").path("env").path("RELEASE_TAG").asText());
        assertEquals("${{ steps.verify_release.outputs.commit }}", verify.path("outputs").path("commit").asText());
        assertEquals("python3 -B scripts/central_publication.py ci", step(verify, "verify_ci").path("run").asText());
        assertEquals("${{ steps.verify_release.outputs.commit }}", step(verify, "verify_ci").path("env").path("RELEASE_COMMIT").asText());
        assertFalse(verify.toString().contains("secrets."));
    }

    @Test void onlySigningReceivesPrivateKeysAndOnlyUploadReceivesCentralTokens() throws Exception {
        var document = workflow("release-central");
        assertFalse(document.has("env"));
        var publish = document.path("jobs").path("publish");
        assertEquals("verify", publish.path("needs").asText());
        assertEquals("maven-central", publish.path("environment").asText());
        assertFalse(publish.has("env"));
        List<String> phases = new ArrayList<>();
        for (var candidate : publish.path("steps")) {
            String id = candidate.path("id").asText();
            if (!id.isEmpty()) phases.add(id);
            for (String key : SIGNING) {
                if (id.equals("sign")) assertEquals("${{ secrets." + key + " }}", candidate.path("env").path(key).asText());
                else assertFalse(candidate.toString().contains(key));
            }
            for (String key : CENTRAL) {
                if (id.equals("publish_central")) assertEquals("${{ secrets." + key + " }}", candidate.path("env").path(key).asText());
                else assertFalse(candidate.toString().contains(key));
            }
            assertFalse(candidate.path("continue-on-error").asBoolean());
            if (candidate.path("uses").asText().startsWith("actions/checkout@")) {
                assertEquals("${{ needs.verify.outputs.commit }}", candidate.path("with").path("ref").asText());
                assertFalse(candidate.path("with").path("persist-credentials").asBoolean(true));
            }
            if (candidate.path("uses").asText().startsWith("gradle/actions/setup-gradle@"))
                assertTrue(candidate.path("with").path("cache-disabled").asBoolean());
        }
        assertEquals(List.of("build", "sign", "publish_central"), phases);
        String signing = step(publish, "sign").path("run").asText();
        for (String flag : List.of("-PcentralRelease=true", "--no-configuration-cache", "--no-daemon", "--no-build-cache", "--dependency-verification=strict"))
            assertTrue(signing.contains(flag));
    }

    @Test void recoverySkipsBuildAndSigningAndRetainsOnlyStateWithDefaultSuccessGating() throws Exception {
        var publish = workflow("release-central").path("jobs").path("publish");
        assertFalse(publish.path("continue-on-error").asBoolean());
        for (String id : List.of("build", "sign"))
            assertEquals("inputs.deployment_id == ''", step(publish, id).path("if").asText());
        var upload = step(publish, "publish_central");
        assertFalse(upload.has("if"));
        assertEquals("${{ inputs.deployment_id }}", upload.path("env").path("MAVEN_DEPLOYMENT_ID").asText());
        int artifacts = 0;
        for (var candidate : publish.path("steps")) if (candidate.path("uses").asText().startsWith("actions/upload-artifact@")) {
            artifacts++;
            assertEquals("always()", candidate.path("if").asText());
            assertEquals("build/central-publication/state.json", candidate.path("with").path("path").asText());
        }
        assertEquals(1, artifacts);
        assertFalse(publish.toString().contains("publishPlugins"));
    }

    @Test void ordinaryCiChecksLibraryMetadataWithoutPublisherSecrets() throws Exception {
        var ci = workflow("compatibility");
        for (String key : SIGNING) assertFalse(ci.toString().contains(key));
        for (String key : CENTRAL) assertFalse(ci.toString().contains(key));
        String packaging = ci.path("jobs").path("core-and-packaging").toString();
        assertTrue(packaging.contains("central_publication.py verify-staging"));
        assertTrue(packaging.contains("test_central_publication.py"));
        assertFalse(packaging.contains("central_publication.py publish"));
    }

    private static String commands(@NonNull JsonNode step) {
        return step.path("run").asText().lines().map(String::strip)
                .filter(line -> !line.isBlank() && !line.startsWith("#"))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static Set<String> fields(@NonNull JsonNode object) {
        Set<String> names = new TreeSet<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
