package secondbrain.domain.exceptions;

public class SerializationFailed extends RuntimeException {
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