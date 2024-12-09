package secondbrain.domain.exceptions;

/**
 * Represents a failed tool call
 */
public class FailedTool extends RuntimeException {
    public FailedTool() {
        super();
    }

    public FailedTool(final String message) {
        super(message);
    }

    public FailedTool(final String message, final Throwable cause) {
        super(message, cause);
    }

    public FailedTool(final Throwable cause) {
        super(cause);
    }
}