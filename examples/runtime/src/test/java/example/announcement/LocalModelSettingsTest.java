package example.announcement;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Validates local settings without opening clients or resolving hostnames. */
class LocalModelSettingsTest {
    static final String DIGEST = "sha256:" + "a".repeat(64);

    @Test
    void requiresAnExplicitDigestAndSuppliesLocalDefaults() {
        assertThrows(IllegalArgumentException.class, () -> LocalModelSettings.fromEnvironment(Map.of()));
        var settings = LocalModelSettings.fromEnvironment(Map.of("PROMPTJIG_MODEL", DIGEST));
        assertEquals(DIGEST, settings.model());
        assertEquals("http://127.0.0.1:12434/engines/v1", settings.baseUrl());
        assertEquals(Duration.ofSeconds(120), settings.timeout());
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://example.com:12434/engines/v1", "https://127.0.0.1:12434/engines/v1",
            "http://127.0.0.1/engines/v1", "http://127.0.0.1:12434/v1", "http:/engines/v1",
            "http://user:password@127.0.0.1:12434/engines/v1", "http://127.0.0.1:12434/engines/v1?query=testdata",
            "http://127.0.0.1:0/engines/v1", "http://127.0.0.1:65536/engines/v1", "http://127.0.0.1:12434/engines/v1#fragment-testdata"})
    void refusesEndpointsOutsideTheExplicitLocalModelRunnerBoundary(String endpoint) {
        assertThrows(IllegalArgumentException.class, () -> LocalModelSettings.builder().baseUrl(endpoint)
                .model(DIGEST).timeout(Duration.ofSeconds(5)).build());
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1:1/engines/v1", "http://localhost:12434/engines/v1", "http://[::1]:65535/engines/v1"})
    void acceptsSupportedLoopbackHostsAndPortBoundaries(String endpoint) {
        var settings = LocalModelSettings.builder().baseUrl(endpoint).model(DIGEST).timeout(Duration.ofSeconds(5)).build();
        assertEquals(endpoint, settings.baseUrl());
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 600})
    void honoursEnvironmentOverridesAtBothTimeoutBoundaries(long seconds) {
        var settings = LocalModelSettings.fromEnvironment(Map.of("PROMPTJIG_MODEL", DIGEST,
                "PROMPTJIG_BASE_URL", "http://localhost:4321/engines/v1", "PROMPTJIG_TIMEOUT_SECONDS", Long.toString(seconds)));
        assertEquals("http://localhost:4321/engines/v1", settings.baseUrl());
        assertEquals(Duration.ofSeconds(seconds), settings.timeout());
    }

    @ParameterizedTest
    @ValueSource(strings = {"latest", "ai/qwen3.5:9b-q4_K_XL", "sha256:123", "sha256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    void refusesMutableOrMalformedModelIdentifiers(String model) {
        assertThrows(IllegalArgumentException.class, () -> LocalModelSettings.fromEnvironment(Map.of("PROMPTJIG_MODEL", model)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "601", "not-a-number"})
    void boundsTheExplicitRequestTimeout(String seconds) {
        assertThrows(IllegalArgumentException.class, () -> LocalModelSettings.fromEnvironment(
                Map.of("PROMPTJIG_MODEL", DIGEST, "PROMPTJIG_TIMEOUT_SECONDS", seconds)));
    }
}
