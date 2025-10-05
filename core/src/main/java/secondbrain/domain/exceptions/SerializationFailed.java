package secondbrain.domain.exceptions;

/**
 * Represents an error during object serialization
 */
public class SerializationFailed extends RuntimeException implements InternalException {
    public SerializationFailed() {
        super();
    }

    public SerializationFailed(final String message) {
        super(message);
    }

    public SerializationFailed(final String message, final Throwable cause) {
        super(message, cause);
    }

    public SerializationFailed(final Throwable cause) {
        super(cause);
    }
}