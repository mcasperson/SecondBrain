package secondbrain.domain.exceptions;

/**
 * Represents an exception thrown during decryption
 */
public class DecryptionFailed extends RuntimeException implements InternalException {
    public DecryptionFailed() {
        super();
    }

    public DecryptionFailed(final String message) {
        super(message);
    }

    public DecryptionFailed(final String message, final Throwable cause) {
        super(message, cause);
    }

    public DecryptionFailed(final Throwable cause) {
        super(cause);
    }
}