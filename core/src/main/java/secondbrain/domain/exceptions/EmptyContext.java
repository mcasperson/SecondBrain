package secondbrain.domain.exceptions;

public class EmptyContext extends RuntimeException {
    private String body;
    private int code;

    public String getBody() {
        return body;
    }

    public int getCode() {
        return code;
    }

    public EmptyContext() {
        super();
    }

    public EmptyContext(final String message, final String body, final int code) {
        super(message);
        this.body = body;
        this.code = code;
    }

    public EmptyContext(final String message, final Throwable cause) {
        super(message, cause);
    }

    public EmptyContext(final String message) {
        super(message);
    }

    public EmptyContext(final Throwable cause) {
        super(cause);
    }
}