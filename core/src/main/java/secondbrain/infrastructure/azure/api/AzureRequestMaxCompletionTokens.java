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
        List<AzureRequestMessage> input,
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

    public List<AzureRequestMessage> getInput() {
        return Objects.requireNonNullElse(input, List.of());
    }

    public String generatePromptText() {
        return getInput().stream()
                .map(AzureRequestMessage::content)
                .map(String::trim)
                .collect(Collectors.joining("\n\n"));
    }
}
