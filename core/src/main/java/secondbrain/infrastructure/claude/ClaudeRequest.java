package secondbrain.infrastructure.claude;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A request sent to the Claude Messages API.
 *
 * @param model     The Claude model id
 * @param maxTokens The maximum number of tokens to generate
 * @param system    The optional system prompt
 * @param messages  The user messages, sent in order
 * @param effort    The optional effort level (low, medium, high, xhigh or max)
 */
public record ClaudeRequest(String model, long maxTokens, @Nullable String system, List<String> messages,
                            @Nullable String effort) {
    /**
     * Returns the text of the request, used to generate cache keys and for logging.
     */
    public String generatePromptText() {
        final List<String> text = new ArrayList<>();
        if (system != null) {
            text.add(system);
        }
        text.addAll(messages);
        return String.join("\n", text);
    }
}
