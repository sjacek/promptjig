package io.github.sjacek.promptjig.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ValidationResultTest {
    @TempDir
    Path directory;

    @Test
    void findingsAtTheSameLocationHaveATotalOrderAndIdenticalReports() throws Exception {
        var first = new Diagnostic("schema-subset", Diagnostic.Severity.ERROR, "prompts/test.prompt.yaml", 3, 2, "/a", "Invalid schema");
        var second = new Diagnostic("schema-subset", Diagnostic.Severity.ERROR, "prompts/test.prompt.yaml", 3, 2, "/b", "Invalid schema");
        var info = new Diagnostic("schema-subset", Diagnostic.Severity.INFO, "prompts/test.prompt.yaml", 3, 2, "/a", "Invalid schema");
        var forward = new ValidationResult(List.of(first, second, info, first), 1);
        var reverse = new ValidationResult(List.of(info, second, first), 1);
        assertEquals(forward, reverse);
        assertEquals(3, forward.diagnostics().size());
        assertNotEquals(0, first.compareTo(second));
        assertNotEquals(0, first.compareTo(info));
        assertThrows(UnsupportedOperationException.class, () -> forward.diagnostics().clear());
        forward.writeReports(directory.resolve("forward"));
        reverse.writeReports(directory.resolve("reverse"));
        for (String name : List.of("validation.json", "validation.txt")) {
            assertArrayEquals(Files.readAllBytes(directory.resolve("forward/" + name)),
                    Files.readAllBytes(directory.resolve("reverse/" + name)));
        }
    }

    @Test
    void informationalFindingsDoNotFailValidationAndReportsReplaceStaleErrors() throws Exception {
        var error = new Diagnostic("lock", Diagnostic.Severity.ERROR, "lock.json", 1, 1, "", "Missing fingerprint");
        var failed = new ValidationResult(List.of(error), 2);
        assertFalse(failed.valid());
        failed.writeReports(directory);
        var info = new Diagnostic("lock-baseline", Diagnostic.Severity.INFO, "lock.json", 1, 1, "", "Baseline skipped");
        var passed = new ValidationResult(List.of(info), 2);
        assertTrue(passed.valid());
        passed.writeReports(directory);
        var report = PromptContract.JSON.readTree(directory.resolve("validation.json").toFile());
        assertEquals(1, report.path("reportVersion").asInt());
        assertTrue(report.path("valid").asBoolean());
        assertEquals(2, report.path("contracts").asInt());
        assertEquals(1, report.path("diagnostics").size());
        assertFalse(Files.readString(directory.resolve("validation.txt")).contains("Missing fingerprint"));
    }

    @Test
    void emptyResultProducesAnExplicitSuccessReport() throws Exception {
        var result = new ValidationResult(List.of(), 0);
        assertTrue(result.valid());
        result.writeReports(directory);
        assertEquals("No violations.\n", Files.readString(directory.resolve("validation.txt")));
        assertTrue(PromptContract.JSON.readTree(directory.resolve("validation.json").toFile()).path("diagnostics").isEmpty());
    }

    @Test
    void consoleDiagnosticsCannotInjectAdditionalLogLines() {
        var diagnostic = new Diagnostic("template-parameters", Diagnostic.Severity.ERROR,
                "prompts\\bad\nname.st", 0, -1, "", "Unknown input:\tname\r\nforged entry\u001b[31m");
        assertEquals("prompts/bad?name.st:1:1: [template-parameters] Unknown input:?name??forged entry?[31m", diagnostic.text());
        assertEquals("prompts/bad\nname.st", diagnostic.file());
    }
}
