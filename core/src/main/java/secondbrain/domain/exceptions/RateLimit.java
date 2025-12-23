package secondbrain.domain.exceptions;

/**
 * Represents an issue with a rate limit being exceeded
 */
public class RateLimit extends RuntimeException implements ExternalException {
    private final String body;
    private final int code;

    public RateLimit() {
        super();
        this.body = "";
        this.code = -1;
    }

    public RateLimit(final String message, final Throwable cause) {
        super(message, cause);
        this.body = "";
        this.code = -1;
    }

    public RateLimit(final String message) {
        super(message);
        this.body = "";
        this.code = -1;
    }

    public RateLimit(final Throwable cause) {
        super(cause);
        this.body = "";
        this.code = -1;
    }

    public String getBody() {
        return body;
    }

    public int getCode() {
        return code;
    }
}