package secondbrain.domain.mutex;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * A mock implementation of the {@link Mutex} interface used for testing purposes.
 * This class immediately executes the callback without any locking.
 */
@ApplicationScoped
public class MockMutex implements Mutex {

    @Override
    public <T> T acquire(final long timeout, final String lockName, final MutexCallback<T> callback) {
        return callback.apply();
    }

    @Override
    public <T> T acquire(final String lockName, final MutexCallback<T> callback) {
        return callback.apply();
    }

    @Override
    public <T> Try<T> tryAcquire(final String lockName, final MutexCallback<T> callback) {
        return Try.of(callback::apply);
    }
}
