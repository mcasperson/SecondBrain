package secondbrain.domain.mutex;

/**
 * Callback interface for executing code within a mutex lock.
 */
public interface MutexCallback<T> {
    T apply();
}
