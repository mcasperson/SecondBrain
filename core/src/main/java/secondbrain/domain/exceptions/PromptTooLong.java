package secondbrain.domain.exceptions;

import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents an LLM rejecting a prompt because it exceeds the model's context window. Repeating the same request
 * can not succeed, so this is deliberately not an {@link ExternalException}, which would be retried. Callers that
 * can reduce the size of the prompt should catch this exception and try again with less context.
 */
public class PromptTooLong extends RuntimeException {
    private static final Pattern TOKEN_COUNTS = Pattern.compile("(\\d+)\\s+tokens\\s*>\\s*(\\d+)\\s+maximum");

    private final int tokens;
    private final int maxTokens;

    public PromptTooLong(final String message, final Throwable cause) {
        super(message, cause);
        final Matcher matcher = TOKEN_COUNTS.matcher(String.valueOf(cause.getMessage()));
        if (matcher.find()) {
            this.tokens = Integer.parseInt(matcher.group(1));
            this.maxTokens = Integer.parseInt(matcher.group(2));
        } else {
            this.tokens = -1;
            this.maxTokens = -1;
        }
    }

    /**
     * Returns the number of tokens in the rejected prompt, if the API reported it.
     */
    public OptionalInt getTokens() {
        return tokens > 0 ? OptionalInt.of(tokens) : OptionalInt.empty();
    }

    /**
     * Returns the maximum number of tokens the model accepts, if the API reported it.
     */
    public OptionalInt getMaxTokens() {
        return maxTokens > 0 ? OptionalInt.of(maxTokens) : OptionalInt.empty();
    }
}
