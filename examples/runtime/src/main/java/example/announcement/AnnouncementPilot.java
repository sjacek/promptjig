package example.announcement;

import example.announcement.generated.AnnouncementExtractionInput;
import example.announcement.generated.AnnouncementExtractionOutput;
import example.announcement.generated.AnnouncementExtractionPromptContract;
import io.github.sjacek.promptjig.springai.PromptContractRegistry;
import io.github.sjacek.promptjig.springai.PromptJsonBinding;
import io.github.sjacek.promptjig.springai.PromptReadResult;
import io.github.sjacek.promptjig.springai.PromptRuntime;
import io.github.sjacek.promptjig.springai.RuntimePromptContract;
import lombok.Builder;
import lombok.NonNull;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.ClassPathResource;

/**
 * The complete flow for a fictional public announcement: validate classpath resources, render,
 * call Spring AI and read a generated response. Construction checks the packaged contract eagerly.
 * The caller owns model selection and the model's transport lifecycle.
 */
public final class AnnouncementPilot {
    private final ChatClient client;
    private final PromptRuntime runtime;
    private final RuntimePromptContract contract;
    private final OpenAiChatOptions responseOptions;

    @Builder
    private AnnouncementPilot(@NonNull ChatModel model) {
        runtime = PromptRuntime.builder().binding(PromptJsonBinding.jackson3()).build();
        contract = PromptContractRegistry.load(new ClassPathResource(AnnouncementExtractionPromptContract.RESOURCE))
                .get(AnnouncementExtractionPromptContract.ID);
        // Fail at startup if packaged resources and generated classes came from different contract versions.
        if (!AnnouncementExtractionPromptContract.VERSION_REF.equals(contract.versionRef())
                || !AnnouncementExtractionPromptContract.FINGERPRINT.equals(contract.fingerprint())) {
            throw new IllegalStateException("Generated announcement model does not match the packaged contract");
        }
        // A fresh OpenAiChatOptions builder carries a default model. Mutate the selected model's options
        // so adding a response schema cannot replace its pinned digest, token limit or timeout.
        var defaults = model.getOptions();
        var options = defaults instanceof OpenAiChatOptions openAi ? openAi.mutate()
                : OpenAiChatOptions.builder().model(defaults.getModel());
        responseOptions = options.responseFormat(OpenAiChatModel.ResponseFormat.builder()
                .jsonSchema(runtime.responseFormat(contract)).strict(true).build()).build();
        client = ChatClient.builder(model).build();
    }

    /**
     * Extracts one announcement using schema-derived messages and response options.
     * The reader returns repairs or schema rejection as findings. Provider/transport failures propagate,
     * while missing generations or blank text fail before JSON reading. An accepted result still needs review.
     *
     * @param documentText public announcement text supplied as a named template parameter
     * @return generated output or rejection findings, without source values in those findings
     * @throws IllegalStateException when the chat response has no generation or usable text
     */
    public PromptReadResult<AnnouncementExtractionOutput> extract(@NonNull String documentText) {
        var messages = runtime.prompt(contract, new AnnouncementExtractionInput(documentText));
        // Spring AI adds the OpenAI envelope. The runtime supplies only the bare contract schema.
        var response = client.prompt(new Prompt(messages, responseOptions)).call().chatResponse();
        if (response == null) throw new IllegalStateException("Model returned no response");
        var generation = response.getResult();
        if (generation == null) throw new IllegalStateException("Model returned no generation");
        var text = generation.getOutput().getText();
        if (text == null || text.isBlank()) throw new IllegalStateException("Model returned no response text");
        // This example handles only the bundled fictional public document and has no audit persistence.
        return runtime.read(contract, text, AnnouncementExtractionOutput.class);
    }
}
