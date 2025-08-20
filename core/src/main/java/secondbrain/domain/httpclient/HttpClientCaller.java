package secondbrain.domain.httpclient;

public interface HttpClientCaller {
    <T> T call(ClientBuilder builder, ClientCallback callback, ResponseCallback responseCallback, ExceptionBuilder exceptionBuilder);
}
