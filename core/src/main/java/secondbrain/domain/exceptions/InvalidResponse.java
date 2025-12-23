package secondbrain.domain.exceptions;

/**
 * Represents an error returned from an HTTP request
 */
public class InvalidResponse extends RuntimeException implements ExternalException {
    private final String body;
    private final int code;

    public InvalidResponse() {
        super();
        this.body = "";
        this.code = -1;
    }

    public InvalidResponse(final String message, final String body, final int code) {
        super(message);
        this.body = body;
        this.code = code;
    }

    public InvalidResponse(final String message, final Throwable cause) {
        super(message, cause);
        this.body = "";
        this.code = -1;
    }

    public InvalidResponse(final String message) {
        super(message);
        this.body = "";
        this.code = -1;
    }

    public InvalidResponse(final Throwable cause) {
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