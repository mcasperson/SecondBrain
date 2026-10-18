package secondbrain.domain.test;

import secondbrain.domain.timeout.TimeoutFunctionCallback;
import secondbrain.domain.timeout.TimeoutService;

import java.util.concurrent.CompletableFuture;

/**
 * Executes callbacks synchronously on the calling thread. The timeout callback is never invoked.
 */
public final class DirectTimeoutService implements TimeoutService {
    @Override
    public <T> T executeWithTimeout(final TimeoutFunctionCallback<T> callback, final TimeoutFunctionCallback<T> onTimeout, final long timeoutSeconds) {
        return callback.apply();
    }

    @Override
    public <T> T executeWithTimeout(final CompletableFuture<T> callback, final TimeoutFunctionCallback<T> onTimeout, final long timeoutSeconds) {
        return callback.join();
    }

    @Override
    public <T> T executeWithTimeoutAndRetry(final TimeoutFunctionCallback<T> callback, final TimeoutFunctionCallback<T> onTimeout, final long timeoutSeconds, final int retryCount) {
        return callback.apply();
    }

    @Override
    public <T> T executeWithTimeoutAndRetry(final TimeoutFunctionCallback<T> callback, final TimeoutFunctionCallback<T> onTimeout, final long timeoutSeconds, final int retryCount, final long retryDelaySeconds) {
        return callback.apply();
    }
}
