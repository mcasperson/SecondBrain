package secondbrain.domain.mutex;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * A mock implementation of the {@link Semaphore} interface used for testing purposes.
 * This class immediately executes the callback without any concurrency control.
 */
@ApplicationScoped
public class MockSemaphore implements Semaphore {

    @Override
    public <T> T acquire(final int allowed, final long timeoutMilliseconds, final String lockName, final MutexCallback<T> callback) {
        return callback.apply();
    }

    @Override
    public <T> T acquire(final int allowed, final String lockName, final MutexCallback<T> callback) {
        return callback.apply();
    }
}
