package secondbrain.domain.exceptions;

/**
 * Represents a timeout
 */
public class Timeout extends RuntimeException implements ExternalException {
    public Timeout() {
        super();
    }

    public Timeout(final String message) {
        super(message);
    }

    public Timeout(final String message, final Throwable cause) {
        super(message, cause);
    }

    public Timeout(final Throwable cause) {
        super(cause);
    }
}