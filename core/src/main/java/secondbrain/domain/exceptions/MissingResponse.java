package secondbrain.domain.exceptions;

public class MissingResponse extends RuntimeException {
    public MissingResponse() {
        super();
    }

    public MissingResponse(String message) {
        super(message);
    }

    public MissingResponse(String message, Throwable cause) {
        super(message, cause);
    }

    public MissingResponse(Throwable cause) {
        super(cause);
    }
}