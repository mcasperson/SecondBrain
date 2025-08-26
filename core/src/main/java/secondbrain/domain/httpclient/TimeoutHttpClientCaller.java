package secondbrain.domain.httpclient;

import secondbrain.domain.timeout.TimeoutFunctionCallback;

/**
 * Defines a service that builds a client, generates the response, parses the response, builds an exception, and offers reties with a timeout.
 * This service will also take care of closing any resources.
 */
public interface TimeoutHttpClientCaller {
    <T> T call(ClientBuilder builder, ClientCallback callback, ResponseCallback<T> responseCallback, ExceptionBuilder exceptionBuilder,
               TimeoutFunctionCallback<T> timeoutCallback, long timeoutSeconds, long retryDelaySeconds, int maxRetries);
}
