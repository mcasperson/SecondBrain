package secondbrain.domain.httpclient;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import secondbrain.domain.timeout.TimeoutFunctionCallback;
import secondbrain.domain.timeout.TimeoutService;
import secondbrain.domain.tryext.TryExtensions;

@ApplicationScoped
public class TimeoutTryHttpClientCalled implements TimeoutHttpClientCaller {

    @Inject
    private TimeoutService timeoutService;

    @Override
    public <T> T call(
            final ClientBuilder builder,
            final ClientCallback callback,
            final ResponseCallback<T> responseCallback,
            final ExceptionBuilder exceptionBuilder,
            final TimeoutFunctionCallback<T> timeoutCallback,
            final long timeoutSeconds,
            final long retryDelaySeconds,
            final int maxRetries) {
        // Clients are not guaranteed to be thread safe, so we build a new client for each call.
        return timeoutService.executeWithTimeoutAndRetry(() -> TryExtensions.withResources(
                                builder::buildClient,
                                callback::call,
                                responseCallback::handleResponse)
                        .getOrElseThrow(exceptionBuilder::buildException),
                timeoutCallback,
                timeoutSeconds,
                maxRetries,
                retryDelaySeconds
        );
    }
}
