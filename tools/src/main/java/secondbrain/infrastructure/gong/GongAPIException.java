package secondbrain.infrastructure.gong;

public class GongAPIException extends RuntimeException {
    public GongAPIException(String message) {
        super(message);
    }

    public GongAPIException(String message, Throwable cause) {
        super(message, cause);
    }
}
