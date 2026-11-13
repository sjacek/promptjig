package io.github.sjacek.promptjig.core;

/**
 * A portable source location and a stable validation rule.
 *
 * @param rule machine-readable rule identifier
 * @param severity whether the finding prevents validation from succeeding
 * @param file project-relative location; separators are normalized to {@code /}
 * @param line one-based line, clamped to at least one when unavailable
 * @param column one-based column, clamped to at least one when unavailable
 * @param pointer JSON Pointer within a structured source, or an empty string for file-level findings
 * @param message explanation without template contents or sensitive input values
 */
public record Diagnostic(String rule, Severity severity, String file, int line, int column,
                         String pointer, String message) implements Comparable<Diagnostic> {
    /** Effect of a diagnostic on the validation result. */
    public enum Severity {
        /** Prevents successful validation and lock updates. */
        ERROR,
        /** Explains a skipped or empty validation step without failing it. */
        INFO
    }

    /**
     * Normalizes separators and unavailable source coordinates.
     * @param rule stable rule identifier
     * @param severity effect on validation
     * @param file portable source location
     * @param line one-based line, clamped to one
     * @param column one-based column, clamped to one
     * @param pointer structured-source location
     * @param message safe explanation
     */
    public Diagnostic {
        file = file.replace('\\', '/');
        line = Math.max(1, line);
        column = Math.max(1, column);
    }

    @Override
    public int compareTo(Diagnostic other) {
        int result = file.compareTo(other.file);
        if (result == 0) result = Integer.compare(line, other.line);
        if (result == 0) result = Integer.compare(column, other.column);
        if (result == 0) result = rule.compareTo(other.rule);
        if (result == 0) result = message.compareTo(other.message);
        // Distinct findings can share a source coordinate and message; retain a total, traversal-independent order.
        if (result == 0) result = pointer.compareTo(other.pointer);
        if (result == 0) result = severity.compareTo(other.severity);
        return result;
    }

    /** {@return a single-line compiler-style location and message, with control characters replaced by {@code ?}} */
    public String text() {
        return safe(file) + ":" + line + ":" + column + ": [" + safe(rule) + "] " + safe(message);
    }

    private static String safe(String value) {
        // Also remove bidi/format controls and Unicode line separators. A diagnostic
        // must not forge another terminal line or visually reverse its location/rule.
        return value.replaceAll("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]", "?");
    }
}
