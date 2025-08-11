package secondbrain.domain.timeout;

public interface TimeoutService {
    <T> T executeWithTimeout(TimeoutFunctionCallback<T> callback, TimeoutFunctionCallback<T> onTimeout, final long timeoutSeconds);

    <T> T executeWithTimeoutAndRetry(TimeoutFunctionCallback<T> callback, TimeoutFunctionCallback<T> onTimeout, final long timeoutSeconds, final int retryCount);

    <T> T executeWithTimeoutAndRetry(TimeoutFunctionCallback<T> callback, TimeoutFunctionCallback<T> onTimeout, final long timeoutSeconds, final int retryCount, final long retryDelaySeconds);
}
