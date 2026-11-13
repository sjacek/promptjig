package io.github.sjacek.promptjig.springai;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.Builder;
import lombok.NonNull;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.UrlResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import static org.junit.jupiter.api.Assertions.*;

/** A loopback control proves requests would be observed before testing the pre-I/O guard. */
@NullMarked
class PromptResourceSecurityTest {
    @TempDir Path directory;

    @Test void localFileAndSpringBootStyleJarResourcesRemainLoadable() {
        for (String location : new String[] {"file:/synthetic folder/prompts/runtime/", "file:///synthetic/prompts/runtime/",
                "jar:file:/synthetic.jar!/prompts/runtime/", "jar:nested:/synthetic.jar/!BOOT-INF/classes!/prompts/runtime/"}) {
            var resource = LocatedResource.builder().location(location).name("rendering.prompt.yaml").reads(new AtomicInteger()).build();
            assertEquals("runtime.rendering", PromptContractRegistry.load(resource).get("runtime.rendering").id());
            assertEquals(3, resource.reads.get(), "Contract and both authored templates must be read");
        }
    }

    @Test void remoteFileAuthoritiesAndEncodedUncPathsAreRejectedBeforeResourceIo() {
        for (String location : new String[] {"file://external.invalid/prompts/", "file:////external/prompts/", "file:/%2Fexternal/prompts/",
                "jar:file://external.invalid/archive.jar!/prompts/", "jar:nested://external/archive.jar!/prompts/"}) {
            var reads = new AtomicInteger();
            var resource = LocatedResource.builder().location(location).name("rendering.prompt.yaml").reads(reads).build();
            assertThrows(IllegalArgumentException.class, () -> PromptContractRegistry.load(resource));
            assertEquals(0, reads.get());
        }
    }

    /** No real Boot loader or network dependency: only the resource's location uses its documented URL form. */
    @Builder
    private static final class LocatedResource extends AbstractResource {
        private final @NonNull String location;
        private final @NonNull String name;
        private final @NonNull AtomicInteger reads;

        @Override public String getDescription() { return "synthetic located prompt resource"; }
        @Override public String getFilename() { return name; }
        @Override public InputStream getInputStream() throws IOException {
            reads.incrementAndGet();
            return new ClassPathResource("prompts/runtime/" + name).getInputStream();
        }
        @Override public LocatedResource createRelative(@NonNull String relativePath) {
            return LocatedResource.builder().location(location).name(relativePath).reads(reads).build();
        }
        @Override
        public URL getURL() throws IOException {
            return new URL(null, location + name, new URLStreamHandler() {
                @Override protected URLConnection openConnection(@NonNull URL url) throws IOException {
                    throw new IOException("The fixture must never open a URL connection");
                }
            });
        }
    }

    @Test void aLocalTemplateSymlinkCannotEscapeTheContractDirectory() throws Exception {
        Path prompts = Files.createDirectory(directory.resolve("prompts"));
        for (String name : new String[] {"rendering.prompt.yaml", "rendering.user.st"}) {
            try (var input = new ClassPathResource("prompts/runtime/" + name).getInputStream()) {
                Files.copy(input, prompts.resolve(name));
            }
        }
        Path outside = directory.resolve("outside.st");
        try (var input = new ClassPathResource("prompts/runtime/rendering.system.st").getInputStream()) {
            Files.copy(input, outside);
        }
        try {
            Files.createSymbolicLink(prompts.resolve("rendering.system.st"), outside);
        } catch (UnsupportedOperationException | java.io.IOException unavailable) {
            Assumptions.abort("This filesystem does not permit creating a test symlink");
        }
        assertThrows(IllegalArgumentException.class,
                () -> PromptContractRegistry.load(new FileSystemResource(prompts.resolve("rendering.prompt.yaml"))));
    }

    @Test void rejectsRemoteContractsAndRemoteArchivesBeforeOpeningAConnection() throws Exception {
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/contract.prompt.yaml";
            try (var input = URI.create(base).toURL().openStream()) { input.readAllBytes(); }
            assertEquals(1, requests.get(), "Positive control must reach the observer");
            for (String location : new String[] {base, "jar:" + base + "!/contract.prompt.yaml"}) {
                assertThrows(IllegalArgumentException.class,
                        () -> PromptContractRegistry.load(new UrlResource(location)));
                assertThrows(IllegalArgumentException.class,
                        () -> PromptContractRegistry.load(new PathMatchingResourcePatternResolver(), location));
            }
            assertEquals(1, requests.get(), "Registry must reject remote URLs before I/O");
        } finally {
            server.stop(0);
        }
    }
}
