package secondbrain.domain.exceptions;

/**
 * Represents a failure from an external source. This is like a 500 response code in HTTP.
 * It means if you make the same call with the same data you might be successful.
 */
public class ExternalFailure extends RuntimeException implements ExternalException {
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