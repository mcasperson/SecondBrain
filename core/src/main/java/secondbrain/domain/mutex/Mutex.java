package secondbrain.domain.mutex;

/**
 * Represents a mutex lock. Typically, this is used to implement cross-process locking.
 */
public interface Mutex {
    <T> T acquire(long timeout, String lockFile, MutexCallback<T> callback);
}
