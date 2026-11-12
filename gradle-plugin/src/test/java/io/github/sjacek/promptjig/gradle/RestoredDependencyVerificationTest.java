package io.github.sjacek.promptjig.gradle;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** A new consumer with restored downloads must not inherit a prior build's verification decision. */
class RestoredDependencyVerificationTest {
    @TempDir Path directory;

    @Test void nativeStrictVerificationRejectsCorruptionInRestoredModuleCache() throws Exception {
        byte[] jar = "owned synthetic dependency bytes".getBytes(StandardCharsets.UTF_8);
        String pom = "<project><modelVersion>4.0.0</modelVersion><groupId>org.example</groupId>"
                + "<artifactId>cache-testdata</artifactId><version>1.0</version></project>";
        // File repositories bypass the downloaded artifact cache. A loopback-only HTTP repository
        // exercises the same cache path as Maven Central without accessing an external service.
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var artifacts = Map.of(
                "/org/example/cache-testdata/1.0/cache-testdata-1.0.jar", jar,
                "/org/example/cache-testdata/1.0/cache-testdata-1.0.pom", pom.getBytes(StandardCharsets.UTF_8));
        server.createContext("/", exchange -> {
            try {
                byte[] content = artifacts.get(exchange.getRequestURI().getPath());
                if (content == null) {
                    exchange.sendResponseHeaders(404, -1);
                } else if (exchange.getRequestMethod().equals("HEAD")) {
                    exchange.getResponseHeaders().set("Content-Length", Integer.toString(content.length));
                    exchange.sendResponseHeaders(200, -1);
                } else {
                    exchange.sendResponseHeaders(200, content.length);
                    exchange.getResponseBody().write(content);
                }
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            String repository = "http://127.0.0.1:" + server.getAddress().getPort();
            Path first = project("first", repository, jar, pom);
            Path original = directory.resolve("original-home");
            resolve(first, original, false, 0);
            assertTrue(Files.exists(first.resolve("resolved")));

            Path restored = directory.resolve("restored-home");
            Path modules = original.resolve("caches/modules-2");
            int corrupted = 0;
            try (var paths = Files.walk(modules)) {
                for (Path source : paths.filter(Files::isRegularFile).filter(path -> !path.toString().endsWith(".lock")).toList()) {
                    Path target = restored.resolve("caches/modules-2").resolve(modules.relativize(source));
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target);
                    if (source.getFileName().toString().equals("cache-testdata-1.0.jar")) {
                        Files.writeString(target, "corrupted restored bytes");
                        corrupted++;
                    }
                }
            }
            assertEquals(1, corrupted, "The HTTP dependency must actually be downloaded into modules-2");
            Path second = project("second", repository, jar, pom);
            String failure = resolve(second, restored, true, 1);
            assertTrue(failure.contains("Dependency verification failed"), failure);
            assertTrue(failure.contains("cache-testdata-1.0.jar"), failure);
            assertFalse(Files.exists(second.resolve("resolved")), "Rejected cached bytes must not complete resolution");
        } finally {
            server.stop(0);
        }
    }

    private Path project(@NonNull String name, @NonNull String repository, byte @NonNull [] jar, @NonNull String pom) throws Exception {
        Path project = Files.createDirectories(directory.resolve(name));
        Files.writeString(project.resolve("gradle.properties"), "org.gradle.jvmargs=-Xmx256m\norg.gradle.workers.max=2\n");
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'verification-testdata'\n");
        Files.writeString(project.resolve("build.gradle"), """
                repositories { maven { url = uri('%s'); allowInsecureProtocol = true } }
                configurations { downloaded }
                dependencies { downloaded 'org.example:cache-testdata:1.0' }
                tasks.register('resolve') {
                    doLast { configurations.downloaded.files.each { println it.name }; file('resolved').text = 'yes' }
                }
                """.formatted(repository));
        Path policy = project.resolve("gradle/verification-metadata.xml");
        Files.createDirectories(policy.getParent());
        Files.writeString(policy, """
                <verification-metadata xmlns="https://schema.gradle.org/dependency-verification">
                  <configuration><verify-metadata>true</verify-metadata><verify-signatures>false</verify-signatures></configuration>
                  <components><component group="org.example" name="cache-testdata" version="1.0">
                    <artifact name="cache-testdata-1.0.jar"><sha256 value="%s"/></artifact>
                    <artifact name="cache-testdata-1.0.pom"><sha256 value="%s"/></artifact>
                  </component></components>
                </verification-metadata>
                """.formatted(hash(jar), hash(pom.getBytes(StandardCharsets.UTF_8))));
        return project;
    }

    private static String hash(byte @NonNull [] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    /** Use a single-use CLI process: Tooling API daemons keep temporary user homes locked on Windows. */
    private static String resolve(@NonNull Path project, @NonNull Path cache, boolean offline, int expectedExit) throws Exception {
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        var command = new ArrayList<String>();
        if (windows) command.addAll(List.of("cmd.exe", "/d", "/c"));
        command.add(Path.of(System.getProperty("promptjig.testGradleHome"), "bin", windows ? "gradle.bat" : "gradle").toString());
        command.addAll(List.of("resolve", "--no-daemon", "--gradle-user-home", cache.toString(),
                "--no-configuration-cache", "--dependency-verification=strict", "--stacktrace", "--console=plain"));
        if (offline) command.add("--offline");
        Path log = project.resolve("resolve.log");
        var process = new ProcessBuilder(command).directory(project.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(3, TimeUnit.MINUTES), "Isolated verification build timed out");
            String output = Files.readString(log);
            assertEquals(expectedExit, process.exitValue(), output);
            return output;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
