package secondbrain.domain.exceptions;

public class InsufficientContext extends RuntimeException {
    private String body;
    private int code;

    public InsufficientContext() {
        super();
    }

    public InsufficientContext(final String message, final String body, final int code) {
        super(message);
        this.body = body;
        this.code = code;
    }

    public InsufficientContext(final String message, final Throwable cause) {
        super(message, cause);
    }

    public InsufficientContext(final String message) {
        super(message);
    }

    public InsufficientContext(final Throwable cause) {
        super(cause);
    }

    public String getBody() {
        return body;
    }

    public int getCode() {
        return code;
    }
}