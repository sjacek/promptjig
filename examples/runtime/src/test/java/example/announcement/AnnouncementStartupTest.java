package example.announcement;

import example.announcement.generated.AnnouncementExtractionPromptContract;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.model.ChatModel;
import static org.junit.jupiter.api.Assertions.*;

/** Simulates deployment drift using resource copies; authored files, generated code and locks stay intact. */
class AnnouncementStartupTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"version", "template", "schema", "reader-policy", "malformed-contract", "missing-template"})
    void rejectsDriftedOrInvalidPackagedContractsBeforeModelAccess(String changed) throws Exception {
        String contractPath = AnnouncementExtractionPromptContract.RESOURCE;
        String prefix = contractPath.substring(0, contractPath.lastIndexOf('/') + 1);
        for (String path : new String[]{contractPath, prefix + "system.st", prefix + "user.st"}) {
            if (changed.equals("missing-template") && path.equals(prefix + "user.st")) continue;
            Path destination = directory.resolve(path);
            Files.createDirectories(destination.getParent());
            String content = AnnouncementPilotTest.resource(path);
            if (changed.equals("version") && path.equals(contractPath)) {
                int currentVersion = AnnouncementExtractionPromptContract.VERSION;
                content = content.replace("version: " + currentVersion, "version: " + (currentVersion + 1));
            }
            if (changed.equals("template") && path.equals(prefix + "system.st")) content += "!";
            if (path.equals(contractPath)) {
                if (changed.equals("schema")) content = content.replace("[IN_PERSON, ONLINE, HYBRID, null]", "[IN_PERSON, ONLINE, null]");
                if (changed.equals("reader-policy")) content = content.replace("strict: false", "strict: true");
                if (changed.equals("malformed-contract")) content = content.replace("promptContract: 1", "promptContract: 99");
            }
            Files.writeString(destination, content, StandardCharsets.UTF_8);
        }
        var calls = new AtomicInteger();
        ChatModel forbidden = prompt -> {
            calls.incrementAndGet();
            throw new AssertionError("A mismatched contract must fail before the model is called");
        };
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        // Shadow only this contract's resources; Java classes and SDK dependencies still come from the parent.
        try (var resources = new URLClassLoader(new URL[]{directory.toUri().toURL()}, original) {
            @Override
            public @Nullable URL getResource(String name) {
                // Missing resources must stay missing instead of falling back to the untouched parent fixture.
                if (name.startsWith(prefix)) return findResource(name);
                return super.getResource(name);
            }
        }) {
            thread.setContextClassLoader(resources);
            boolean invalid = changed.equals("malformed-contract") || changed.equals("missing-template");
            Class<? extends RuntimeException> expectedType = invalid ? IllegalArgumentException.class : IllegalStateException.class;
            var failure = assertThrows(expectedType,
                    () -> AnnouncementPilot.builder().model(forbidden).build());
            if (!invalid) assertEquals("Generated announcement model does not match the packaged contract", failure.getMessage());
        } finally {
            thread.setContextClassLoader(original);
        }
        assertEquals(0, calls.get());
    }
}
