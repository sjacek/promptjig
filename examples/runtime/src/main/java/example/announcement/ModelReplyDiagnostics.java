package example.announcement;

import com.openai.models.completions.CompletionUsage;
import lombok.Builder;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.EmptyUsage;

/**
 * Provider metadata captured before reading assistant JSON, without storing source or reasoning text.
 * Null counters mean the backend did not report them; they must not be interpreted as zero usage.
 * @param finishReason provider finish reason as exposed by Spring AI (for example STOP or LENGTH)
 * @param promptTokens reported input token count
 * @param completionTokens reported output token count, including reasoning when the provider counts it
 * @param totalTokens reported total token count
 * @param reasoningTokens native OpenAI reasoning count, when reported
 * @param outputCharacters assistant text length, or null if no generation was returned
 */
@Builder
public record ModelReplyDiagnostics(@Nullable String finishReason, @Nullable Integer promptTokens,
        @Nullable Integer completionTokens, @Nullable Integer totalTokens, @Nullable Long reasoningTokens,
        @Nullable Integer outputCharacters) {
    /**
     * Captures metadata even when a response has no generation or no assistant text.
     * @param response Spring AI response, or null when the provider returned none
     * @return metadata with explicit unknowns rather than fabricated counters
     */
    public static ModelReplyDiagnostics from(@Nullable ChatResponse response) {
        var generation = response == null ? null : response.getResult();
        var usage = response == null ? null : response.getMetadata().getUsage();
        if (usage instanceof EmptyUsage) usage = null;
        var text = generation == null ? null : generation.getOutput().getText();
        return builder().finishReason(generation == null ? null : generation.getMetadata().getFinishReason())
                .promptTokens(usage == null ? null : usage.getPromptTokens())
                .completionTokens(usage == null ? null : usage.getCompletionTokens())
                .totalTokens(usage == null ? null : usage.getTotalTokens())
                .reasoningTokens(usage != null && usage.getNativeUsage() instanceof CompletionUsage nativeUsage
                        ? nativeUsage.completionTokensDetails().flatMap(CompletionUsage.CompletionTokensDetails::reasoningTokens).orElse(null)
                        : null)
                .outputCharacters(generation == null ? null : text == null ? 0 : text.length()).build();
    }

    /** {@return whether the provider stopped because the generation token budget was exhausted} */
    public boolean truncated() { return "length".equalsIgnoreCase(finishReason); }
}
