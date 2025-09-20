package secondbrain.domain.exceptions;

/**
 * Represents a failure from an internal source. This is like a 400 response code in HTTP.
 * It means that if you make the same call with the same data you'll get the same result.
 */
public class InternalFailure extends RuntimeException implements InternalException {
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