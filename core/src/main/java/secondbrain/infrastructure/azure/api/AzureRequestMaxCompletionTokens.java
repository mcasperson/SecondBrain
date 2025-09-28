package secondbrain.infrastructure.azure.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

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
        @Nullable @JsonProperty("max_completion_tokens") Integer maxOutputTokens,
        String model) implements PromptTextGenerator {

    public AzureRequestMaxCompletionTokens(final List<AzureRequestMessage> messages, final String model) {
        this(messages,
                null,
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

    public AzureRequestMaxCompletionTokens updateMessages(List<AzureRequestMessage> newMessages) {
        return new AzureRequestMaxCompletionTokens(newMessages, this.maxOutputTokens, this.model);
    }
}
