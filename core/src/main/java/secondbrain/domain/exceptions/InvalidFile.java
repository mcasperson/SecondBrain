package secondbrain.domain.exceptions;

/**
 * Represent an error regarding an invalid filename or ability to access a file
 */
public class InvalidFile extends RuntimeException implements InternalException {

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