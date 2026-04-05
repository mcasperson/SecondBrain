package secondbrain.infrastructure.azure.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
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
 * This record then supports the chat completions endpoint, specifically version 2024-05-01-preview.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AzureRequestMaxCompletionTokens_2025_04_01_preview(
        @Nullable List<AzureRequestMessage> input,
        @Nullable @JsonProperty("max_completion_tokens") Integer maxOutputTokens,
        @Nullable @JsonProperty("reasoning_effort") String reasoningEffort,
        String model) implements PromptTextGenerator {

    public AzureRequestMaxCompletionTokens_2025_04_01_preview(final List<AzureRequestMessage> messages, final String model) {
        this(messages, null, null, model);
    }

    public List<AzureRequestMessage> getMessages() {
        return Objects.requireNonNullElse(input, List.of());
    }

    public String generatePromptText() {
        return getMessages().stream()
                .map(AzureRequestMessage::content)
                .map(String::trim)
                .collect(Collectors.joining("\n\n"));
    }

    public PromptTextGenerator updateMessages(List<AzureRequestMessage> newMessages) {
        return new AzureRequestMaxCompletionTokens_2025_04_01_preview(newMessages, this.maxOutputTokens, this.reasoningEffort, this.model);
    }
}
