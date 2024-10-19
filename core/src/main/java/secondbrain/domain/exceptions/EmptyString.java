package secondbrain.domain.exceptions;

public class EmptyString extends RuntimeException {
    public EmptyString() {
        super();
    }

    public EmptyString(final String message) {
        super(message);
    }

    public EmptyString(final String message, final Throwable cause) {
        super(message, cause);
    }

    public EmptyString(final Throwable cause) {
        super(cause);
    }
}