package secondbrain.domain.exceptions;

public class InvalidResponse extends RuntimeException {
    private String body;
    private int code;

    public String getBody() {
        return body;
    }

    public int getCode() {
        return code;
    }

    public InvalidResponse() {
        super();
    }

    public InvalidResponse(final String message, final String body, final int code) {
        super(message);
        this.body = body;
        this.code = code;
    }

    public InvalidResponse(final String message, final Throwable cause) {
        super(message, cause);
    }

    public InvalidResponse(final String message) {
        super(message);
    }

    public InvalidResponse(final Throwable cause) {
        super(cause);
    }
}