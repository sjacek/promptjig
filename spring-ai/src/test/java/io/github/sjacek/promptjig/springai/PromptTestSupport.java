package io.github.sjacek.promptjig.springai;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import org.jspecify.annotations.NullMarked;
import org.springframework.ai.chat.messages.Message;

/** Text assertions for rendered templates; Spring AI also supports messages without a text body. */
@NullMarked
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class PromptTestSupport {
    static String text(@NonNull Message message) {
        var text = message.getText();
        if (text == null) throw new AssertionError("Expected a text body in a rendered template message");
        return text;
    }
}
