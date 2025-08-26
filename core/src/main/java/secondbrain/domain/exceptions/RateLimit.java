package secondbrain.domain.exceptions;

/**
 * Represents an issue with a rate limit being exceeded
 */
public class RateLimit extends RuntimeException {
    private String body;
    private int code;

    public RateLimit() {
        super();
    }

    public RateLimit(final String message, final Throwable cause) {
        super(message, cause);
    }

    public RateLimit(final String message) {
        super(message);
    }

    public RateLimit(final Throwable cause) {
        super(cause);
    }

    public String getBody() {
        return body;
    }

    public int getCode() {
        return code;
    }
}