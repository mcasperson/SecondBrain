package secondbrain.domain.exceptions;

/**
 * Represents an exception thrown during deserialization
 */
public class DeserializationFailed extends RuntimeException implements InternalException {
    public DeserializationFailed() {
        super();
    }

    public DeserializationFailed(final String message) {
        super(message);
    }

    public DeserializationFailed(final String message, final Throwable cause) {
        super(message, cause);
    }

    public DeserializationFailed(final Throwable cause) {
        super(cause);
    }
}