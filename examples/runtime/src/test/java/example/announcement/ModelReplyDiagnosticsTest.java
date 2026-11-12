package example.announcement;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import static org.junit.jupiter.api.Assertions.*;

/** Checks absent response metadata without substituting invented zero token counts. */
class ModelReplyDiagnosticsTest {
    @Test
    void absentResponseHasUnknownCountersAndNoClaimOfTruncation() {
        var metadata = ModelReplyDiagnostics.from(null);
        assertNull(metadata.finishReason());
        assertNull(metadata.promptTokens());
        assertNull(metadata.completionTokens());
        assertNull(metadata.totalTokens());
        assertNull(metadata.reasoningTokens());
        assertNull(metadata.outputCharacters());
        assertFalse(metadata.truncated());
    }

    @Test
    void emptyGenerationListDoesNotInventUsage() {
        var metadata = ModelReplyDiagnostics.from(new ChatResponse(List.of()));
        assertNull(metadata.finishReason());
        assertNull(metadata.completionTokens());
        assertNull(metadata.outputCharacters());
    }
}
