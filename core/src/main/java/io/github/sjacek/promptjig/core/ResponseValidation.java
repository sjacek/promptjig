package io.github.sjacek.promptjig.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Comparator;
import java.util.List;
import lombok.Builder;
import lombok.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Validated response tree and safe findings. Rejected responses expose no raw or partially repaired tree.
 * The accepted tree remains sensitive and mutable; callers own it and must not use its contents as log messages.
 * @param normalized accepted object, owned by this result, or null on failure
 * @param violations immutable findings sorted by pointer, rule, action, type and length
 */
@Builder
public record ResponseValidation(@Nullable JsonNode normalized, @NonNull List<ResponseViolation> violations) {
    /**
     * Freezes the findings; does not retain the original response.
     * @param normalized accepted object or null
     * @param violations safe findings
     */
    public ResponseValidation {
        violations = violations.stream().sorted(Comparator.comparing(ResponseViolation::pointer)
                .thenComparing(ResponseViolation::rule).thenComparing(ResponseViolation::action)
                .thenComparing(ResponseViolation::valueType).thenComparingInt(ResponseViolation::valueLength)).toList();
    }

    /** {@return whether a schema-valid object is available} */
    public boolean accepted() { return normalized != null; }

    /** Returns counts only, never the accepted response content. */
    @Override
    public String toString() { return "ResponseValidation[accepted=" + accepted() + ", violations=" + violations.size() + "]"; }
}
