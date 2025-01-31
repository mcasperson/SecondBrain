package secondbrain.domain.exceptions;

/**
 * Represents a failure from an internal source
 */
public class InternalFailure extends RuntimeException {
    public InternalFailure() {
        super();
    }

    public InternalFailure(final String message) {
        super(message);
    }

    public InternalFailure(final String message, final Throwable cause) {
        super(message, cause);
    }

    public InternalFailure(final Throwable cause) {
        super(cause);
    }
}