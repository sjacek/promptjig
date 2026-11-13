package io.github.sjacek.promptjig.maven;

import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import lombok.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Element;
import static org.junit.jupiter.api.Assertions.*;

/** Inspect the packaged, generated descriptor: annotations alone cannot prove what native Maven will discover. */
class PluginDescriptorTest {
    @Test void packagedDescriptorBindsOnlyValidateAndGenerateAndKeepsHostApisOutOfTheJar() throws Exception {
        String location = System.getProperty("promptjig.mavenPluginJar");
        assertNotNull(location, "Run through the Gradle test task which supplies the current plugin JAR");
        try (var jar = new ZipFile(Path.of(location).toFile())) {
            assertNotNull(jar.getEntry("META-INF/LICENSE"));
            assertFalse(jar.stream().anyMatch(entry -> entry.getName().startsWith("org/apache/maven/")));
            Element plugin = descriptor(jar);
            assertEquals("promptjig", text(plugin, "goalPrefix"));
            assertEquals("io.github.sjacek.promptjig", text(plugin, "groupId"));
            assertEquals("promptjig-maven-plugin", text(plugin, "artifactId"));
            assertEquals(System.getProperty("promptjig.candidateVersion"), text(plugin, "version"));
            var mojos = plugin.getElementsByTagName("mojo");
            var phases = new TreeMap<String, String>();
            for (int index = 0; index < mojos.getLength(); index++) {
                var mojo = (Element) mojos.item(index);
                phases.put(text(mojo, "goal"), text(mojo, "phase"));
                assertEquals("true", text(mojo, "threadSafe"));
                assertTrue(text(mojo, "implementation").startsWith("io.github.sjacek.promptjig.maven."));
                var parameters = mojo.getElementsByTagName("parameter");
                boolean baseline = false;
                for (int p = 0; p < parameters.getLength(); p++) {
                    var parameter = (Element) parameters.item(p);
                    if (text(parameter, "name").equals("baselineLockFile")) {
                        baseline = true;
                        assertEquals("false", text(parameter, "required"));
                    }
                }
                assertTrue(baseline, "Every goal must accept the repository's explicit baseline");
            }
            assertEquals(Map.of("validate", "validate", "generate", "generate-sources", "lock", ""), phases);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"validate", "generate", "lock"})
    void packagedParametersKeepDefaultsCliOverridesAndRequiredFlags(@NonNull String goal) throws Exception {
        String location = System.getProperty("promptjig.mavenPluginJar");
        assertNotNull(location);
        try (var jar = new ZipFile(Path.of(location).toFile())) {
            var mojos = descriptor(jar).getElementsByTagName("mojo");
            Element selected = null;
            for (int index = 0; index < mojos.getLength(); index++) {
                var mojo = (Element) mojos.item(index);
                if (text(mojo, "goal").equals(goal)) selected = mojo;
            }
            assertNotNull(selected, "Maven must discover the selected goal");
            var defaults = new TreeMap<>(Map.of(
                    "source", "${project.basedir}/src/main/resources/prompts",
                    "lockFile", "${project.basedir}/prompt-contracts.lock.json",
                    "reportDirectory", "${project.build.directory}/reports/prompt-contracts"));
            if (goal.equals("generate")) defaults.putAll(Map.of(
                    "jackson", "v2", "resourcePrefix", "prompts",
                    "outputDirectory", "${project.build.directory}/generated-sources/prompt-contracts"));
            if (goal.equals("lock")) defaults.put("coordinationFile", "${project.build.directory}/promptjig/lock-write.lck");
            Element configuration = (Element) selected.getElementsByTagName("configuration").item(0);
            assertNotNull(configuration);
            for (var entry : defaults.entrySet()) {
                Element value = (Element) configuration.getElementsByTagName(entry.getKey()).item(0);
                assertNotNull(value, entry.getKey());
                assertEquals(entry.getValue(), value.getAttribute("default-value"), entry.getKey());
                assertEquals("${promptjig." + entry.getKey() + "}", value.getTextContent());
            }
            Element baseline = (Element) configuration.getElementsByTagName("baselineLockFile").item(0);
            assertNotNull(baseline);
            assertFalse(baseline.hasAttribute("default-value"), "The adapter must never invent a trusted baseline");
            assertEquals("${promptjig.baselineLockFile}", baseline.getTextContent());
            var parameters = selected.getElementsByTagName("parameter");
            var required = new TreeMap<String, String>();
            for (int index = 0; index < parameters.getLength(); index++) {
                var parameter = (Element) parameters.item(index);
                required.put(text(parameter, "name"), text(parameter, "required"));
                if (text(parameter, "name").equals("projectDirectory"))
                    assertEquals("false", text(parameter, "editable"), "The module root belongs to Maven");
            }
            for (String name : new String[]{"projectDirectory", "source", "lockFile", "reportDirectory"})
                assertEquals("true", required.get(name), name);
            assertEquals("false", required.get("baselineLockFile"));
            if (goal.equals("generate")) assertEquals("true", required.get("packageName"));
        }
    }

    /** Read the actual packaged descriptor without permitting external XML input. */
    private static Element descriptor(@NonNull ZipFile jar) throws Exception {
        var entry = jar.getEntry("META-INF/maven/plugin.xml");
        assertNotNull(entry);
        var parser = DocumentBuilderFactory.newInstance();
        parser.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        parser.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        parser.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        try (var input = jar.getInputStream(entry)) {
            return parser.newDocumentBuilder().parse(input).getDocumentElement();
        }
    }

    private static String text(@NonNull Element parent, @NonNull String name) {
        var elements = parent.getElementsByTagName(name);
        return elements.getLength() == 0 ? "" : elements.item(0).getTextContent();
    }
}
