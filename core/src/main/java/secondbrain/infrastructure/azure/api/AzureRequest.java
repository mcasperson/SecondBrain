package secondbrain.infrastructure.azure.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AzureRequest(
        List<AzureRequestMessage> messages,
        @JsonProperty("max_tokens") Integer maxTokens,
        String model,
        Float temperature,
        @JsonProperty("top_p") Float topP,
        @JsonProperty("presence_penalty") Float presencePenalty,
        @JsonProperty("frequency_penalty") Float frequencyPenalty) {

    public static final int DEFAULT_OUTPUT_TOKENS = 2048;
    public static final int DEFAULT_INPUT_TOKENS = 16384 - DEFAULT_OUTPUT_TOKENS;
    private static final float DEFAULT_TEMPERATURE = 0.8f;
    private static final float DEFAULT_TOP_P = 0.1f;
    private static final float DEFAULT_PRESSURE_PENALTY = 0;
    private static final float DEFAULT_FREQUENCY_PENALTY = 0;

    public AzureRequest(final List<AzureRequestMessage> messages, final String model) {
        this(messages,
                model,
                DEFAULT_OUTPUT_TOKENS);
    }

    public AzureRequest(final List<AzureRequestMessage> messages, final String model, final Integer maxTokens) {
        this(messages,
                maxTokens,
                model,
                DEFAULT_TEMPERATURE,
                DEFAULT_TOP_P,
                DEFAULT_PRESSURE_PENALTY,
                DEFAULT_FREQUENCY_PENALTY);
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
