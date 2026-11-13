package io.github.sjacek.promptjig.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Terminal text cannot inject lines, ANSI escapes or misleading bidi direction. */
class DiagnosticSecurityTest {
    @Test void sanitizesEveryUntrustedTextFieldWithoutMutatingStructuredEvidence() {
        var diagnostic = new Diagnostic("rule\n\u202e", Diagnostic.Severity.ERROR,
                "source\u2028\u001b[31m", 1, 2, "/value", "synthetic\r\n\u2066message\u2029");
        assertEquals("source??[31m:1:2: [rule??] synthetic???message?", diagnostic.text());
        assertEquals("rule\n\u202e", diagnostic.rule());
        assertTrue(diagnostic.message().contains("\n"), "Structured fields retain their original values");
        assertEquals("source:1:1: [rule] ?", new Diagnostic("rule", Diagnostic.Severity.ERROR,
                "source", 1, 1, "", "\u0085").text());
    }
}
