package example.announcement;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import java.net.Proxy;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import lombok.Builder;
import lombok.NonNull;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;

/**
 * Explicit local inference settings, validated before opening an SDK client.
 * The CLI reads these settings only after selecting its local-model mode; defaults never select a model.
 *
 * @param baseUrl loopback HTTP base URL with explicit port and the Model Runner /engines/v1 path
 * @param model installed model's full lowercase sha256 digest
 * @param timeout per-request timeout between one and 600 seconds, inclusive
 * @param maxTokens generation budget between one and 8192, including model reasoning
 */
@Builder
public record LocalModelSettings(@NonNull String baseUrl, @NonNull String model, @NonNull Duration timeout, int maxTokens) {
    /**
     * Validates the local endpoint shape, digest and timeout before a transport can be opened.
     * @param baseUrl absolute loopback Model Runner URL with explicit port
     * @param model full immutable model digest
     * @param timeout bounded request timeout
     * @param maxTokens bounded generation budget
     */
    public LocalModelSettings {
        URI uri = URI.create(baseUrl);
        String host = uri.getHost();
        boolean loopback = "127.0.0.1".equals(host) || "localhost".equals(host) || "[::1]".equals(host);
        if (!"http".equals(uri.getScheme()) || !loopback
                || uri.getPort() < 1 || uri.getPort() > 65535 || !"/engines/v1".equals(uri.getPath())
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Use an explicit loopback HTTP endpoint ending in /engines/v1");
        }
        if (!model.matches("sha256:[0-9a-f]{64}")) throw new IllegalArgumentException("Use a full sha256 model digest");
        if (timeout.compareTo(Duration.ofSeconds(1)) < 0 || timeout.compareTo(Duration.ofSeconds(600)) > 0) {
            throw new IllegalArgumentException("Timeout must be between 1 and 600 seconds");
        }
        if (maxTokens < 1 || maxTokens > 8192) throw new IllegalArgumentException("Max tokens must be between 1 and 8192");
    }

    /**
     * Reads an explicitly selected model plus optional endpoint/timeout overrides.
     * No environment is consulted implicitly by the record or its builder.
     * @param environment caller-supplied environment snapshot
     * @return validated settings; this operation opens no connections
     * @throws IllegalArgumentException for a missing digest or invalid setting
     */
    public static LocalModelSettings fromEnvironment(@NonNull Map<String, String> environment) {
        String digest = environment.get("PROMPTJIG_MODEL");
        if (digest == null) throw new IllegalArgumentException("Set PROMPTJIG_MODEL to the installed local model digest");
        return builder().baseUrl(environment.getOrDefault("PROMPTJIG_BASE_URL", "http://127.0.0.1:12434/engines/v1"))
                .model(digest).timeout(Duration.ofSeconds(Long.parseLong(environment.getOrDefault("PROMPTJIG_TIMEOUT_SECONDS", "600"))))
                .maxTokens(Integer.parseInt(environment.getOrDefault("PROMPTJIG_MAX_TOKENS", "4096")))
                .build();
    }

    /**
     * Opens one SDK client shared by Spring AI's sync/async adapters.
     * Zero retries and the request timeout belong to the SDK as well as the exposed model options.
     * @return caller-owned session; close it after successful extraction or any failure
     */
    public Session open() {
        var transport = SpringAiOpenAiHttpClient.builder().timeout(timeout).proxy(Proxy.NO_PROXY).build();
        var sdk = new OpenAIClientImpl(ClientOptions.builder().httpClient(transport).baseUrl(baseUrl)
                .apiKey("local-pilot-testdata").timeout(timeout).maxRetries(0).build());
        try {
            var chatModel = OpenAiChatModel.builder().openAiClient(sdk).openAiClientAsync(sdk.async())
                    .options(OpenAiChatOptions.builder().model(model).temperature(0.0).maxTokens(maxTokens)
                            .maxRetries(0).timeout(timeout).build()).build();
            return Session.builder().client(sdk).model(chatModel).build();
        } catch (RuntimeException exception) {
            sdk.close();
            throw exception;
        }
    }

    /**
     * Owns the transport used by both model adapters. The model must be used within this session's lifetime.
     * @param client SDK client whose close releases the transport
     * @param model Spring AI adapter sharing that client
     */
    @Builder
    public record Session(@NonNull OpenAIClient client, @NonNull ChatModel model) implements AutoCloseable {
        @Override
        public void close() { client.close(); }
    }
}
