package secondbrain.domain.exceptions;

/**
 * Represents a failure from an external source
 */
public class ExternalFailure extends RuntimeException {
    public ExternalFailure() {
        super();
    }

    public ExternalFailure(final String message) {
        super(message);
    }

    public ExternalFailure(final String message, final Throwable cause) {
        super(message, cause);
    }

    public ExternalFailure(final Throwable cause) {
        super(cause);
    }
}