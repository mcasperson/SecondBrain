package secondbrain.domain.exceptions;

/**
 * Represents an exception thrown during decompression
 */
public class DecompressionFailed extends RuntimeException implements InternalException {
    public DecompressionFailed() {
        super();
    }

    public DecompressionFailed(final String message) {
        super(message);
    }

    public DecompressionFailed(final String message, final Throwable cause) {
        super(message, cause);
    }

    public DecompressionFailed(final Throwable cause) {
        super(cause);
    }
}