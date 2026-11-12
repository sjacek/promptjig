package example.announcement;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises CLI routing with isolated environment/output snapshots, without invoking a local model. */
class PilotApplicationTest {
    @Test
    void syntheticDefaultIgnoresLocalModelEnvironmentAndPrintsTypedUtf8Output() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            // Poisoned local settings prove that environment presence cannot opt the CLI into inference.
            PilotApplication.run(new String[0], Map.of("PROMPTJIG_MODEL", "mutable-testdata",
                    "PROMPTJIG_BASE_URL", "https://example.invalid", "PROMPTJIG_TIMEOUT_SECONDS", "invalid-testdata"), output);
            output.println("caller-owned-stream-testdata");
            assertFalse(output.checkError(), "The CLI must leave its caller's output stream open");
        }
        var lines = bytes.toString(StandardCharsets.UTF_8).lines().toList();
        assertEquals(3, lines.size());
        assertEquals("mode=synthetic; contract=events.announcement-extraction-v1; accepted=true; findings=0", lines.get(0));
        var json = JsonMapper.builder().build().readTree(lines.get(1));
        if (json == null) throw new AssertionError("Missing CLI output JSON");
        assertEquals("Wieczór z Javą", json.path("title").asString());
        assertEquals("2026-12-10", json.path("date").asString());
        assertEquals("IN_PERSON", json.path("format").asString());
        assertEquals("Przykładowy Dom Kultury", json.path("location").asString());
        assertTrue(json.path("free").asBoolean());
        assertEquals("caller-owned-stream-testdata", lines.get(2));
    }

    @ParameterizedTest
    @MethodSource("invalidArguments")
    void invalidArgumentsFailBeforeProducingDemoOutput(String[] arguments) {
        var bytes = new ByteArrayOutputStream();
        try (var output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            assertThrows(IllegalArgumentException.class, () -> PilotApplication.run(arguments, Map.of(), output));
        }
        assertEquals(0, bytes.size());
    }

    private static Stream<Arguments> invalidArguments() {
        return Stream.of(new String[]{"--unknown-testdata"}, new String[]{""}, new String[]{"--local-model=tag"},
                new String[]{"--local-case="}, new String[]{"--local-case=../unknown-testdata"},
                new String[]{"--local-model", "extra-testdata"}, new String[]{"--local-model", "--local-model"})
                .map(arguments -> Arguments.of((Object) arguments));
    }

    @ParameterizedTest
    @ValueSource(strings = {"--local-model", "--local-suite", "--local-case=online-event-testdata"})
    void explicitLocalModeRequiresModelSettingsAndNeverFallsBackToTheSyntheticDemo(String mode) {
        var bytes = new ByteArrayOutputStream();
        try (var output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> PilotApplication.run(new String[]{mode}, Map.of(), output));
            assertEquals("Set PROMPTJIG_MODEL to the installed local model digest", failure.getMessage());
        }
        assertEquals(0, bytes.size());
    }
}
