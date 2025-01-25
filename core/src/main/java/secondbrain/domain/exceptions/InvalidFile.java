package secondbrain.domain.exceptions;

public class InvalidFile extends RuntimeException {

    public InvalidFile() {
        super();
    }

    public InvalidFile(final String message, final Throwable cause) {
        super(message, cause);
    }

    public InvalidFile(final String message) {
        super(message);
    }

    public InvalidFile(final Throwable cause) {
        super(cause);
    }
}