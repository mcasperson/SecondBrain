package secondbrain.domain.exceptions;

public class EmptyList extends RuntimeException {
    public EmptyList() {
        super();
    }

    public EmptyList(String message) {
        super(message);
    }

    public EmptyList(String message, Throwable cause) {
        super(message, cause);
    }

    public EmptyList(Throwable cause) {
        super(cause);
    }
}