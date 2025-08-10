package secondbrain.domain.timeout;

public interface TimeoutService {
    <T> T executeWithTimeout(TimeoutFunctionCallback<T> callback, TimeoutFunctionCallback<T> onTimeout, final long timeoutSeconds);
}
