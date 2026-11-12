package io.github.sjacek.promptjig.gradle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import lombok.NonNull;
import org.gradle.testkit.runner.GradleRunner;

/** Every isolated consumer gets the producer's checksum policy before resolving cached or new dependencies. */
final class VerifiedTestKit {
    private VerifiedTestKit() { }

    static GradleRunner create(@NonNull Path project, @NonNull Path metadata) {
        try {
            Path target = project.resolve("gradle/verification-metadata.xml");
            Files.createDirectories(target.getParent());
            Files.copy(metadata, target, StandardCopyOption.REPLACE_EXISTING);
            trustCurrentCandidate(target);
            return GradleRunner.create().withProjectDir(project.toFile());
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot install dependency verification policy in TestKit", failure);
        }
    }

    /** A file-repository publication is external to Gradle's resolver. Trust only this build's exact candidate
     * bytes in the temporary fixture policy, never a whole group or a persistent/shared baseline. */
    private static void trustCurrentCandidate(@NonNull Path metadata) {
        String repository = System.getProperty("promptjig.stagingRepository");
        String version = System.getProperty("promptjig.candidateVersion");
        if (repository == null || version == null) return; // Download-only warmup has no local publication.
        if (!version.matches("\\d+\\.\\d+\\.\\d+(?:-SNAPSHOT)?")) throw new IllegalArgumentException("Invalid candidate version");
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var document = factory.newDocumentBuilder().parse(metadata.toFile());
            String namespace = "https://schema.gradle.org/dependency-verification";
            var components = document.getElementsByTagNameNS(namespace, "components").item(0);
            for (String artifact : new String[]{"promptjig-gradle-plugin", "io.github.sjacek.promptjig.gradle.plugin"}) {
                var component = document.createElementNS(namespace, "component");
                component.setAttribute("group", "io.github.sjacek.promptjig");
                component.setAttribute("name", artifact);
                component.setAttribute("version", version);
                Path directory = Path.of(repository, "io/github/sjacek/promptjig", artifact, version);
                for (String extension : new String[]{"pom", "module", "jar"}) {
                    Path file = directory.resolve(artifact + "-" + version + "." + extension);
                    if (!Files.isRegularFile(file)) continue;
                    if (Files.isSymbolicLink(file)) throw new IOException("Candidate must not be a symlink");
                    var entry = document.createElementNS(namespace, "artifact");
                    entry.setAttribute("name", file.getFileName().toString());
                    var checksum = document.createElementNS(namespace, "sha256");
                    checksum.setAttribute("value", sha256(file));
                    checksum.setAttribute("origin", "Current producer build; scoped to this temporary fixture");
                    entry.appendChild(checksum);
                    component.appendChild(entry);
                }
                if (!component.hasChildNodes()) throw new IOException("Current candidate publication is missing");
                components.appendChild(component);
            }
            var transformers = TransformerFactory.newInstance();
            transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            transformers.newTransformer().transform(new DOMSource(document), new StreamResult(metadata.toFile()));
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot pin current local candidate in temporary TestKit policy", failure);
        }
    }

    private static String sha256(@NonNull Path path) throws IOException {
        try (var stream = Files.newInputStream(path)) {
            var hash = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int length;
            while ((length = stream.read(buffer)) != -1) hash.update(buffer, 0, length);
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
