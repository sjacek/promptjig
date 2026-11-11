package io.github.sjacek.promptjig.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class TemplateParametersTest {
    static Stream<Object[]> templates() {
        return Stream.of(
                new Object[]{"attribute", Set.of("ocrText")},
                new Object[]{"nested", Set.of("contract")},
                new Object[]{"conditional", Set.of("flag", "known", "hidden")},
                new Object[]{"function", Set.of("values")},
                new Object[]{"anonymous", Set.of("values", "suffix")},
                new Object[]{"nested-anonymous", Set.of("groups", "suffix", "group")},
                new Object[]{"dynamic", Set.of("contract", "key")},
                new Object[]{"comment", Set.of("ocrText")},
                new Object[]{"static", Set.of()});
    }

    @ParameterizedTest
    @MethodSource("templates")
    void readsAllBranchesAndRespectsLocalNames(String name, Set<String> expected) throws Exception {
        assertEquals(expected, TemplateParameters.read(resource(name)));
    }

    @Test
    void rejectsSyntaxErrors() {
        assertThrows(IllegalArgumentException.class, () -> TemplateParameters.read(resource("broken")));
    }

    @Test
    void retainsFirstReferenceLocationsWhileSortingNames() throws Exception {
        var analysis = TemplateParameters.analyze(resource("repeated"));
        assertTrue(analysis.problems().isEmpty());
        assertEquals(java.util.List.of(new TemplateParameters.Parameter("alpha", 2, 9),
                new TemplateParameters.Parameter("beta", 2, 2)), analysis.parameters());
        assertThrows(UnsupportedOperationException.class, () -> analysis.parameters().clear());
    }

    @Test
    void rejectsExternalCallsWithoutDisclosingTemplateContent() throws Exception {
        String template = resource("external");
        var analysis = TemplateParameters.analyze(template);
        assertFalse(analysis.problems().isEmpty());
        assertTrue(analysis.problems().stream().allMatch(problem -> problem.line() >= 1 && problem.column() >= 1
                && !problem.message().contains("ocrText")));
        assertThrows(IllegalArgumentException.class, () -> TemplateParameters.read(template));
    }

    private static String resource(String name) throws Exception {
        try (var stream = TemplateParametersTest.class.getResourceAsStream("/prompts/parameters/" + name + ".st")) {
            assertNotNull(stream);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
