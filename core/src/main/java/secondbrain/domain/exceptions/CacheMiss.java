package secondbrain.domain.exceptions;

/**
 * Represents a cache miss
 */
public class CacheMiss extends RuntimeException {
    public CacheMiss() {
        super();
    }

    public CacheMiss(final String message) {
        super(message);
    }

    public CacheMiss(final String message, final Throwable cause) {
        super(message, cause);
    }

    public CacheMiss(final Throwable cause) {
        super(cause);
    }
}