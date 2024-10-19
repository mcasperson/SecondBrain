package secondbrain.domain.exceptions;

public class InvalidResponse extends RuntimeException {
    public InvalidResponse() {
        super();
    }

    public InvalidResponse(final String message) {
        super(message);
    }

    public InvalidResponse(final String message, final Throwable cause) {
        super(message, cause);
    }

    public InvalidResponse(final Throwable cause) {
        super(cause);
    }
}