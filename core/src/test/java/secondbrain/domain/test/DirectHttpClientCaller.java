package secondbrain.domain.test;

import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.core.Response;
import secondbrain.domain.httpclient.ClientBuilder;
import secondbrain.domain.httpclient.ClientCallback;
import secondbrain.domain.httpclient.ExceptionBuilder;
import secondbrain.domain.httpclient.ResponseCallback;
import secondbrain.domain.timeout.TimeoutFunctionCallback;
import secondbrain.domain.httpclient.TimeoutHttpClientCaller;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Synchronous {@link TimeoutHttpClientCaller} for unit tests. It executes the client callbacks once, on the calling
 * thread, and converts any failure with the caller supplied exception builder. An optional per-call interceptor can
 * throw an exception directly, simulating a caller that surfaces the raw HTTP failure.
 */
public final class DirectHttpClientCaller implements TimeoutHttpClientCaller {
    private final List<Long> retryDelays = new ArrayList<>();
    private final List<Integer> maxRetries = new ArrayList<>();
    private final List<Supplier<RuntimeException>> directFailures = new ArrayList<>();
    private int calls;

    /**
     * Queue an exception to be thrown as-is (without the exception builder) for the next call.
     */
    public DirectHttpClientCaller failNextWith(final Supplier<RuntimeException> failure) {
        directFailures.add(failure);
        return this;
    }

    public int calls() {
        return calls;
    }

    public List<Long> retryDelays() {
        return retryDelays;
    }

    public List<Integer> maxRetries() {
        return maxRetries;
    }

    @Override
    public <T> T call(final ClientBuilder builder, final ClientCallback callback, final ResponseCallback<T> responseCallback,
                      final ExceptionBuilder exceptionBuilder, final TimeoutFunctionCallback<T> timeoutCallback,
                      final long timeoutSeconds, final long retryDelaySeconds, final int retries) {
        calls++;
        retryDelays.add(retryDelaySeconds);
        maxRetries.add(retries);

        if (!directFailures.isEmpty()) {
            throw directFailures.removeFirst().get();
        }

        try {
            final Client client = builder.buildClient();
            final Response response = callback.call(client);
            return responseCallback.handleResponse(response);
        } catch (final RuntimeException e) {
            throw exceptionBuilder.buildException(e);
        }
    }
}
