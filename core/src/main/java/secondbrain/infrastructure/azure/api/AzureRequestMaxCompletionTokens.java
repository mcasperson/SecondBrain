package secondbrain.infrastructure.azure.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Uses max_completion_tokens rather than max_tokens. This is required for newer OpenAI models.
 * See <a href="https://community.openai.com/t/why-was-max-tokens-changed-to-max-completion-tokens/938077">this discussion</a>.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AzureRequestMaxCompletionTokens(
        List<AzureRequestMessage> messages,
        @JsonProperty("max_completion_tokens") Integer maxCompletionTokens,
        String model) implements PromptTextGenerator {

    public static final int DEFAULT_OUTPUT_TOKENS = 2048;

    public AzureRequestMaxCompletionTokens(final List<AzureRequestMessage> messages, final String model) {
        this(messages,
                DEFAULT_OUTPUT_TOKENS,
                model);
    }

    public List<AzureRequestMessage> getMessages() {
        return Objects.requireNonNullElse(messages, List.of());
    }

    public String generatePromptText() {
        return getMessages().stream()
                .map(AzureRequestMessage::content)
                .map(String::trim)
                .collect(Collectors.joining("\n\n"));
    }
}
