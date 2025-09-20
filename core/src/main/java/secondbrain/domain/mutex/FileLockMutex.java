package secondbrain.domain.mutex;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import secondbrain.domain.exceptions.LockFail;

import java.io.RandomAccessFile;
import java.nio.channels.FileLock;

@ApplicationScoped
public class FileLockMutex implements Mutex {
    private static final long SLEEP = 1000;

    @Override
    public <T> T acquire(final long timeout, final String lockFile, final MutexCallback<T> callback) {
        return Try.withResources(() -> new RandomAccessFile(lockFile, "rw").getChannel())
                .of(channel -> Try.withResources(() -> channel.tryLock(0, Long.MAX_VALUE, false))
                        .of(lock -> callIfNotNull(lock, callback))
                        .recover(LockFail.class, ex -> {
                            if (timeout <= 0) {
                                throw new LockFail("Failed to obtain file lock within the specified timeout");
                            }
                            Try.run(() -> Thread.sleep(Math.min(SLEEP, timeout)));
                            return acquire(Math.max(timeout - SLEEP, 0), lockFile, callback);
                        })
                        .get())
                .get();
    }

    private <T> T callIfNotNull(final FileLock lock, final MutexCallback<T> callback) {
        if (lock != null) {
            return callback.apply();
        }

        throw new LockFail("Failed to obtain file lock");
    }
}
