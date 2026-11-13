package io.github.sjacek.promptjig.gradle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.util.List;
import lombok.NonNull;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Security jobs consume the warmup in parallel with tests and preserve failures without secrets. */
class SecurityWorkflowTest {
    private static JsonNode document(@NonNull String name) throws Exception {
        try (var input = SecurityWorkflowTest.class.getResourceAsStream("/workflows/" + name + ".yml")) {
            assertNotNull(input);
            return new YAMLMapper().readTree(input);
        }
    }

    @Test void candidateScansAndOfflineAcceptanceAreMandatorySecretFreeConsumers() throws Exception {
        var workflow = document("compatibility");
        for (String name : List.of("dependency-security", "offline-security")) {
            var job = workflow.path("jobs").path(name);
            assertEquals("warm-cache", job.path("needs").asText());
            assertEquals("${{ !cancelled() }}", job.path("if").asText());
            assertFalse(job.toString().contains("secrets."));
            boolean retained = false;
            for (var step : job.path("steps")) {
                assertFalse(step.path("continue-on-error").asBoolean());
                if (step.path("uses").asText().startsWith("actions/upload-artifact@")) {
                    assertEquals("always()", step.path("if").asText());
                    assertEquals("error", step.path("with").path("if-no-files-found").asText());
                    assertEquals(name + "-${{ github.run_id }}-${{ github.run_attempt }}", step.path("with").path("name").asText(),
                            "A retry must preserve the previous attempt's failed evidence");
                    retained = true;
                }
            }
            assertTrue(retained, "Failed security evidence must be retained: " + name);
        }
        String scan = workflow.path("jobs").path("dependency-security").toString();
        for (String required : List.of("dependency_policy.py", "test_security*.py", "security_inventory.py",
                "security_reports.py prepare --commit", "security_reports.py scan", "--refresh",
                "anchore/scan-action/download-grype@27805bf3b4e84b4a5c980df22ed233c00390a439"))
            assertTrue(scan.contains(required), required);
        assertFalse(scan.contains("--only-fixed"));
        assertTrue(workflow.path("jobs").path("offline-security").toString().contains("offline_security.py"));
    }

    @Test void bothRegistriesRequireExactCommitCompatibilityBeforeCredentials() throws Exception {
        var portal = document("release").path("jobs").path("publish");
        var central = document("release-central").path("jobs").path("verify");
        for (var job : List.of(portal, central)) {
            boolean guard = false;
            for (var step : job.path("steps")) {
                if (step.path("id").asText().equals("verify_ci")) {
                    assertTrue(step.path("run").asText().contains("scripts/central_publication.py ci"));
                    assertEquals("${{ github.token }}", step.path("env").path("GITHUB_TOKEN").asText());
                    assertFalse(step.path("continue-on-error").asBoolean());
                    assertFalse(step.has("if"));
                    guard = true;
                }
                if (step.toString().contains("secrets.")) assertTrue(guard);
            }
            assertTrue(guard);
        }
    }
}
