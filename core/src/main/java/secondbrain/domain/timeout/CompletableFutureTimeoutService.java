package secondbrain.domain.timeout;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

import static com.google.common.base.Preconditions.checkNotNull;

@ApplicationScoped
public class CompletableFutureTimeoutService implements TimeoutService {
    @Inject
    private Logger logger;

    @Override
    public <T> T executeWithTimeout(final TimeoutFunctionCallback<T> callback, final TimeoutFunctionCallback<T> onTimeout, final long timeoutSeconds) {
        checkNotNull(callback, "callback must not be null");
        checkNotNull(onTimeout, "onTimeout must not be null");

        return Try.of(() -> CompletableFuture
                        .supplyAsync(callback::apply)
                        .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                        .get())
                .onFailure(TimeoutException.class, e -> logger.warning("Operation timed out after " + timeoutSeconds + " seconds"))
                .recover(TimeoutException.class, e -> onTimeout.apply())
                .get();
    }

    @Override
    public <T> T executeWithTimeout(final CompletableFuture<T> callback, final TimeoutFunctionCallback<T> onTimeout, final long timeoutSeconds) {
        checkNotNull(callback, "callback must not be null");
        checkNotNull(onTimeout, "onTimeout must not be null");

        return Try.of(() -> callback
                        .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                        .get())
                .onFailure(TimeoutException.class, e -> logger.warning("Operation timed out after " + timeoutSeconds + " seconds"))
                .recover(TimeoutException.class, e -> onTimeout.apply())
                .get();
    }

    @Override
    public <T> T executeWithTimeoutAndRetry(
            final TimeoutFunctionCallback<T> callback,
            final TimeoutFunctionCallback<T> onTimeout,
            final long timeoutSeconds,
            final int retryCount) {
        checkNotNull(callback, "callback must not be null");
        checkNotNull(onTimeout, "onTimeout must not be null");

        return executeWithTimeoutAndRetry(callback, onTimeout, timeoutSeconds, retryCount, 0);
    }

    @Override
    public <T> T executeWithTimeoutAndRetry(
            final TimeoutFunctionCallback<T> callback,
            final TimeoutFunctionCallback<T> onTimeout,
            final long timeoutSeconds,
            final int retryCount,
            final long retryDelaySeconds) {
        checkNotNull(callback, "callback must not be null");
        checkNotNull(onTimeout, "onTimeout must not be null");

        return executeWithTimeoutAndRetry(callback, onTimeout, timeoutSeconds, retryCount, retryDelaySeconds, 0);
    }

    private <T> T executeWithTimeoutAndRetry(
            final TimeoutFunctionCallback<T> callback,
            final TimeoutFunctionCallback<T> onTimeout,
            final long timeoutSeconds,
            final int retryCount,
            final long retryDelaySeconds,
            final int retryAttempt) {
        checkNotNull(callback, "callback must not be null");
        checkNotNull(onTimeout, "onTimeout must not be null");

        if (retryAttempt >= retryCount) {
            logger.warning("Max retry attempts reached: " + retryCount);
            return onTimeout.apply();
        }

        return Try.of(() -> CompletableFuture
                        .supplyAsync(callback::apply)
                        .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                        .get())
                .onFailure(TimeoutException.class, e -> logger.warning("Operation timed out after " + timeoutSeconds + " seconds"))
                .recover(TimeoutException.class, e -> {
                    Try.run(() -> Thread.sleep(retryDelaySeconds * 1000));
                    return executeWithTimeoutAndRetry(
                            callback, onTimeout, timeoutSeconds, retryCount, retryDelaySeconds, retryAttempt + 1);
                })
                .get();
    }
}
