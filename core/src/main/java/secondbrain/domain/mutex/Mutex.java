package secondbrain.domain.mutex;

import io.vavr.control.Try;

/**
 * Represents a mutex lock. Typically, this is used to implement cross-process locking.
 */
public interface Mutex {
    <T> T acquire(long timeout, String lockName, MutexCallback<T> callback);
    <T> T acquire(String lockName, MutexCallback<T> callback);

    /**
     * Attempts to acquire the mutex. If successful, executes the callback and returns
     * a successful Try. If the lock cannot be obtained, immediately returns a failed Try.
     */
    <T> Try<T> tryAcquire(String lockName, MutexCallback<T> callback);
}
