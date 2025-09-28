package secondbrain.domain.exceptions;

/**
 * Represents a prompt response that we can prove was invalid
 */
public class InvalidAnswer extends RuntimeException implements InternalException {
    public InvalidAnswer() {
        super();
    }

    public InvalidAnswer(final String message) {
        super(message);
    }

    public InvalidAnswer(final String message, final Throwable cause) {
        super(message, cause);
    }

    public InvalidAnswer(final Throwable cause) {
        super(cause);
    }
}