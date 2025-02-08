package secondbrain.domain.exceptions;

/**
 * Represents an error due to insufficient content for a prompt
 */
public class InsufficientContext extends RuntimeException {
    public InsufficientContext() {
        super();
    }

    public InsufficientContext(final String message, final Throwable cause) {
        super(message, cause);
    }

    public InsufficientContext(final String message) {
        super(message);
    }

    public InsufficientContext(final Throwable cause) {
        super(cause);
    }
}