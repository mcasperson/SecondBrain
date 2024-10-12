package secondbrain.domain.exceptions;

public class InvalidResponse extends RuntimeException {
    public InvalidResponse() {
        super();
    }

    public InvalidResponse(String message) {
        super(message);
    }

    public InvalidResponse(String message, Throwable cause) {
        super(message, cause);
    }

    public InvalidResponse(Throwable cause) {
        super(cause);
    }
}