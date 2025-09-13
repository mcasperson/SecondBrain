package secondbrain.infrastructure.azure.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The challenge with this record is supporting the input used by multiple models. OpenAI models hosted by Azure AI Foundry
 * are shown to use the /openai/responses?api-version=2025-04-01-preview endpoint. Other models, like Phi-4,
 * use the completions endpoint /models/chat/completions?api-version=2024-05-01-preview.
 * <p>
 * The /response endpoint appears to adopt newer API versions faster. However, OpenAI models can also use the /completions endpoint.
 * <p>
 * This record then supports the chat completions endpoint, specifically version 2024-05-01-preview.
 * This appears to be the more common endpoint for all models.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AzureRequestMaxCompletionTokens(
        List<AzureRequestMessage> messages,
        @JsonProperty("max_completion_tokens") Integer maxOutputTokens,
        String model) implements PromptTextGenerator {

    public static final int DEFAULT_OUTPUT_TOKENS = 2048;
    public static final int DEFAULT_INPUT_TOKENS = 16384 - DEFAULT_OUTPUT_TOKENS;
    public static final float DEFAULT_CHARS_PER_INPUT_TOKENS = 3.5f;

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
