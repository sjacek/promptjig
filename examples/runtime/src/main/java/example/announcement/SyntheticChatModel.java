package example.announcement;

import java.util.List;
import lombok.Builder;
import lombok.NonNull;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Deterministic model double for the default demo; it performs no inference or network requests.
 * Invalid response fixtures are allowed so tests can exercise the same reader's repair and rejection paths.
 * @param response synthetic assistant content, returned verbatim for every call
 */
@Builder
public record SyntheticChatModel(@NonNull String response) implements ChatModel {
    @Override
    public ChatResponse call(@NonNull Prompt prompt) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(response))));
    }
}
