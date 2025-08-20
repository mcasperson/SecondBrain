package secondbrain.domain.httpclient;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import secondbrain.domain.timeout.TimeoutFunctionCallback;
import secondbrain.domain.timeout.TimeoutService;

@ApplicationScoped
public class TimeoutTryHttpClientCalled implements TimeoutHttpClientCaller {

    @Inject
    private TimeoutService timeoutService;

    @Override
    public <T> T call(
            final ClientBuilder builder,
            final ClientCallback callback,
            final ResponseCallback responseCallback,
            final ExceptionBuilder exceptionBuilder,
            final TimeoutFunctionCallback<T> timeoutCallback,
            final long timeoutSeconds,
            final long retryDelaySeconds,
            final int maxRetries) {
        // Clients are not guaranteed to be thread safe, so we build a new client for each call.
        return timeoutService.executeWithTimeoutAndRetry(() -> Try.withResources(builder::buildClient)
                        .of(client -> Try.withResources(() -> callback.call(client))
                                .of(responseCallback::<T>handleResponse)
                                .get()
                        )
                        .getOrElseThrow(exceptionBuilder::buildException),
                timeoutCallback,
                timeoutSeconds,
                maxRetries,
                retryDelaySeconds
        );
    }
}
