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
        return Try.withResources(builder::buildClient)
                .of(client -> timeoutService.executeWithTimeoutAndRetry(
                                () -> Try.withResources(() -> callback.call(client))
                                        .of(responseCallback::<T>handleResponse)
                                        .get(),
                                timeoutCallback,
                                timeoutSeconds,
                                maxRetries,
                                retryDelaySeconds
                        )
                )
                .getOrElseThrow(exceptionBuilder::buildException);
    }
}
