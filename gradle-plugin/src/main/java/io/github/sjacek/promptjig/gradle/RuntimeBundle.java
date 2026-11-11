package io.github.sjacek.promptjig.gradle;

import java.io.IOException;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipInputStream;

/** Unpacks trusted bundled JARs into task-local ignored state, never into prompt resources. */
final class RuntimeBundle {
    private RuntimeBundle() { }

    static List<File> unpack(Path directory) throws IOException {
        Files.createDirectories(directory);
        List<File> classpath = new ArrayList<>();
        try (var resource = RuntimeBundle.class.getResourceAsStream("/META-INF/promptjig/runtime.zip")) {
            if (resource == null) throw new IOException("Missing bundled Promptjig runtime");
            try (var zip = new ZipInputStream(resource)) {
                java.util.zip.ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (!name.matches("[A-Za-z0-9._+-]+\\.jar")) throw new IOException("Invalid bundled runtime entry");
                    Path file = directory.resolve(name);
                    byte[] bytes = zip.readAllBytes();
                    if (!Files.exists(file) || !Arrays.equals(bytes, Files.readAllBytes(file))) Files.write(file, bytes);
                    classpath.add(file.toFile());
                }
            }
        }
        if (classpath.isEmpty()) throw new IOException("Empty bundled runtime");
        // The isolated loader also needs the WorkAction itself, from either the packaged plugin or TestKit classes.
        try { classpath.add(new File(PromptContractsWork.class.getProtectionDomain().getCodeSource().getLocation().toURI())); }
        catch (java.net.URISyntaxException exception) { throw new IOException(exception); }
        return classpath;
    }
}
