package io.github.sjacek.promptjig.core;

import lombok.Builder;
import lombok.NonNull;

/**
 * Value-free response finding. Unknown property names are omitted: their pointer identifies the containing object.
 * @param pointer JSON Pointer using authored property names and original array indices
 * @param rule stable schema keyword or parsing/binding rule
 * @param action applied repair, or rejection
 * @param valueType JSON type or missing/invalid-json
 * @param valueLength string code points, container members/elements, scalar text length, or zero for null/missing
 */
@Builder
public record ResponseViolation(@NonNull String pointer, @NonNull String rule, @NonNull Action action,
                                @NonNull String valueType, int valueLength) {
    /** Disposition of the affected input value. */
    public enum Action {
        /** Replaced a nullable leaf with JSON null. */
        NULLIFIED,
        /** Removed an unknown property or an invalid array element. */
        REMOVED,
        /** Supplied a required nullable field or required array. */
        FILLED,
        /** No response is admitted. */
        REJECTED
    }
}
