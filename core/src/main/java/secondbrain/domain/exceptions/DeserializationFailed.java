package secondbrain.domain.exceptions;

public class DeserializationFailed extends RuntimeException {
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