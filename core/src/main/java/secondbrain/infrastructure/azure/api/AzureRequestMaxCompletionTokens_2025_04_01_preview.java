package secondbrain.infrastructure.azure.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AzureRequestMaxCompletionTokens_2025_04_01_preview(
        @JsonProperty("input") List<AzureRequestMessage> input,
        @Nullable @JsonProperty("max_output_tokens") Integer maxOutputTokens,
        @Nullable @JsonProperty("reasoning") AzureRequestMaxCompletionTokensReasoning_2025_04_01_preview reasoning,
        @JsonProperty("model") String model) implements PromptTextGenerator {

    public AzureRequestMaxCompletionTokens_2025_04_01_preview(final List<AzureRequestMessage> messages, final String model) {
        this(messages, null, null, model);
    }

    @JsonIgnore
    public List<AzureRequestMessage> getMessages() {
        return Objects.requireNonNullElse(input, List.of());
    }

    public String generatePromptText() {
        return getMessages().stream()
                .map(AzureRequestMessage::content)
                .filter(Objects::nonNull)
                .map(String::trim)
                .collect(Collectors.joining("\n\n"));
    }

    public PromptTextGenerator updateMessages(List<AzureRequestMessage> newMessages) {
        return new AzureRequestMaxCompletionTokens_2025_04_01_preview(newMessages, this.maxOutputTokens, this.reasoning, this.model);
    }

    public String getModel() {
        return model;
    }
}
