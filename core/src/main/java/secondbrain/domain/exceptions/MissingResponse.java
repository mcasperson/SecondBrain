package secondbrain.domain.exceptions;

/**
 * Represents a HTTP 404 response
 */
public class MissingResponse extends RuntimeException {
    public MissingResponse() {
        super();
    }

    public MissingResponse(final String message) {
        super(message);
    }

    public MissingResponse(final String message, final Throwable cause) {
        super(message, cause);
    }

    public MissingResponse(final Throwable cause) {
        super(cause);
    }
}