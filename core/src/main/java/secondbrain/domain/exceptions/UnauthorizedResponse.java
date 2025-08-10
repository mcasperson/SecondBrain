package secondbrain.domain.exceptions;

/**
 * Represents a HTTP 401 response
 */
public class UnauthorizedResponse extends RuntimeException {
    public UnauthorizedResponse() {
        super();
    }

    public UnauthorizedResponse(final String message) {
        super(message);
    }

    public UnauthorizedResponse(final String message, final Throwable cause) {
        super(message, cause);
    }

    public UnauthorizedResponse(final Throwable cause) {
        super(cause);
    }
}