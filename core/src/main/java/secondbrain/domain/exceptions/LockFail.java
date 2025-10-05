package secondbrain.domain.exceptions;

/**
 * Represents a failure to acquire a lock
 */
public class LockFail extends RuntimeException implements ExternalException {
    public LockFail() {
        super();
    }

    public LockFail(final String message) {
        super(message);
    }

    public LockFail(final String message, final Throwable cause) {
        super(message, cause);
    }

    public LockFail(final Throwable cause) {
        super(cause);
    }
}