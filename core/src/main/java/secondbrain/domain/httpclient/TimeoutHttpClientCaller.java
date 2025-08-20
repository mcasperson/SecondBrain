package secondbrain.domain.httpclient;

import secondbrain.domain.timeout.TimeoutFunctionCallback;

public interface TimeoutHttpClientCaller {
    <T> T call(ClientBuilder builder, ClientCallback callback, ResponseCallback responseCallback, ExceptionBuilder exceptionBuilder,
               TimeoutFunctionCallback<T> timeoutCallback, long timeoutSeconds, long retryDelaySeconds, int maxRetries);
}
