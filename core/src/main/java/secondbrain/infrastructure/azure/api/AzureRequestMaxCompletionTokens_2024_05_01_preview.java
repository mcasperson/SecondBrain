package secondbrain.infrastructure.azure.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AzureRequestMaxCompletionTokens_2024_05_01_preview(
        @Nullable List<AzureRequestMessage> messages,
        @Nullable @JsonProperty("max_completion_tokens") Integer maxOutputTokens,
        String model) implements PromptTextGenerator {

    public AzureRequestMaxCompletionTokens_2024_05_01_preview(final List<AzureRequestMessage> messages, final String model) {
        this(messages, null, model);
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

    public PromptTextGenerator updateMessages(List<AzureRequestMessage> newMessages) {
        return new AzureRequestMaxCompletionTokens_2024_05_01_preview(newMessages, this.maxOutputTokens, this.model);
    }
}
