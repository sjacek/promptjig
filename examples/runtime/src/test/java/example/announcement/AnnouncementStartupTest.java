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
    @ValueSource(strings = {"version", "template"})
    void rejectsPackagedResourcesThatDoNotMatchTheGeneratedVersionOrFingerprint(String changed) throws Exception {
        String contractPath = AnnouncementExtractionPromptContract.RESOURCE;
        String prefix = contractPath.substring(0, contractPath.lastIndexOf('/') + 1);
        for (String path : new String[]{contractPath, prefix + "system.st", prefix + "user.st"}) {
            Path destination = directory.resolve(path);
            Files.createDirectories(destination.getParent());
            String content = AnnouncementPilotTest.resource(path);
            if (changed.equals("version") && path.equals(contractPath)) content = content.replace("version: 1", "version: 2");
            if (changed.equals("template") && path.equals(prefix + "system.st")) content += "!";
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
                if (name.startsWith(prefix)) {
                    URL local = findResource(name);
                    if (local != null) return local;
                }
                return super.getResource(name);
            }
        }) {
            thread.setContextClassLoader(resources);
            var failure = assertThrows(IllegalStateException.class, () -> AnnouncementPilot.builder().model(forbidden).build());
            assertEquals("Generated announcement model does not match the packaged contract", failure.getMessage());
        } finally {
            thread.setContextClassLoader(original);
        }
        assertEquals(0, calls.get());
    }
}
