package secondbrain.domain.exceptions;

public class EmptyList extends RuntimeException {
    public EmptyList() {
        super();
    }

    public EmptyList(final String message) {
        super(message);
    }

    public EmptyList(final String message, final Throwable cause) {
        super(message, cause);
    }

    public EmptyList(final Throwable cause) {
        super(cause);
    }
}