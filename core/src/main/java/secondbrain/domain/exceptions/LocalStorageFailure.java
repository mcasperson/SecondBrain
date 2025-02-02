package secondbrain.domain.exceptions;

/**
 * Represents a failure that occurred with the local storage system.
 */
public class LocalStorageFailure extends RuntimeException {
    public LocalStorageFailure() {
        super();
    }

    public LocalStorageFailure(final String message) {
        super(message);
    }

    public LocalStorageFailure(final String message, final Throwable cause) {
        super(message, cause);
    }

    public LocalStorageFailure(final Throwable cause) {
        super(cause);
    }
}