package secondbrain.domain.exceptions;

public class EmptyString extends RuntimeException {
    public EmptyString() {
        super();
    }

    public EmptyString(String message) {
        super(message);
    }

    public EmptyString(String message, Throwable cause) {
        super(message, cause);
    }

    public EmptyString(Throwable cause) {
        super(cause);
    }
}