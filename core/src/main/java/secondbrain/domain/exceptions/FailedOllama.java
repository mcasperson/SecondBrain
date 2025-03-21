package secondbrain.domain.exceptions;

/**
 * Represents a failed call to Ollama
 */
public class FailedOllama extends RuntimeException {
    public FailedOllama() {
        super();
    }

    public FailedOllama(final String message) {
        super(message);
    }

    public FailedOllama(final String message, final Throwable cause) {
        super(message, cause);
    }

    public FailedOllama(final Throwable cause) {
        super(cause);
    }
}