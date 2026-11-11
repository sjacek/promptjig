package io.github.sjacek.promptjig.springai;

import io.github.sjacek.promptjig.core.ResponseViolation;
import java.util.List;
import lombok.Builder;
import lombok.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Typed response and safe validation findings. Applications must audit the raw response before reading it.
 * A non-null value means schema validation and binding succeeded, not that the extraction is approved.
 * Findings can accompany a usable value. The value itself remains sensitive despite the safe toString output.
 * @param value accepted output, or null after rejection
 * @param violations immutable findings without original values
 * @param <T> generated output model type
 */
@Builder
public record PromptReadResult<T>(@Nullable T value, @NonNull List<ResponseViolation> violations) {
    /**
     * Copies findings.
     * @param value accepted model or null
     * @param violations safe findings
     */
    public PromptReadResult { violations = List.copyOf(violations); }
    /** {@return whether strict binding produced an accepted output} */
    public boolean accepted() { return value != null; }
    /** {@return counts only; never invokes the potentially sensitive model's toString} */
    @Override public String toString() { return "PromptReadResult[accepted=" + accepted() + ", violations=" + violations.size() + "]"; }
}
