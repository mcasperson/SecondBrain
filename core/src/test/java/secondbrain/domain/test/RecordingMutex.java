package secondbrain.domain.test;

import io.vavr.control.Try;
import secondbrain.domain.mutex.Mutex;
import secondbrain.domain.mutex.MutexCallback;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Runs callbacks immediately and records the lock names that were requested.
 */
public final class RecordingMutex implements Mutex {
    public final List<String> lockNames = new CopyOnWriteArrayList<>();

    @Override
    public <T> T acquire(final long timeout, final String lockName, final MutexCallback<T> callback) {
        lockNames.add(lockName);
        return callback.apply();
    }

    @Override
    public <T> T acquire(final String lockName, final MutexCallback<T> callback) {
        lockNames.add(lockName);
        return callback.apply();
    }

    @Override
    public <T> Try<T> tryAcquire(final String lockName, final MutexCallback<T> callback) {
        lockNames.add(lockName);
        return Try.of(callback::apply);
    }
}
