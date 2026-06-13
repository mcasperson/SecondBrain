package secondbrain.domain.exceptions;

/**
 * Represents a failure to call an LLM, which may include multiple causes (e.g. multiple failed attempts).
 */
public class LlmCallFailure extends RuntimeException implements ExternalException {
    public LlmCallFailure() {
        super();
    }

    public LlmCallFailure(final String message) {
        super(message);
    }

    public LlmCallFailure(final String message, final Throwable cause) {
        super(message, cause);
    }

    public LlmCallFailure(final Throwable cause) {
        super(cause);
    }
}
