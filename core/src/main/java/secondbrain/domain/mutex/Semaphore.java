package secondbrain.domain.mutex;

/**
 * Represents a semaphore that allows a limited number of concurrent executions.
 * Typically, this is used to implement cross-process concurrency control with a pool of locks.
 */
public interface Semaphore {
    <T> T acquire(int allowed, long timeout, String lockName, MutexCallback<T> callback);
    <T> T acquire(int allowed, String lockName, MutexCallback<T> callback);
}

