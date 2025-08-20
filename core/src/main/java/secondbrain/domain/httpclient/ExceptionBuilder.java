package secondbrain.domain.httpclient;

public interface ExceptionBuilder {
    RuntimeException buildException(Throwable cause);
}
