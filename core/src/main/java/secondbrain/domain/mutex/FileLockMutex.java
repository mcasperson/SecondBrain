package secondbrain.domain.mutex;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import secondbrain.domain.exceptions.LockFail;

import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.util.logging.Logger;

@ApplicationScoped
public class FileLockMutex implements Mutex {
    private static final long SLEEP = 10000;

    @Inject
    private Logger log;

    @Override
    public <T> T acquire(final long timeout, final String lockName, final MutexCallback<T> callback) {
        return Try.withResources(() -> new RandomAccessFile(lockName, "rw").getChannel())
                .of(channel -> Try.withResources(() -> channel.tryLock(0, 0, false))
                        .of(lock -> callIfNotNull(lock, callback))
                        .recover(Throwable.class, ex -> {
                            if (timeout <= 0) {
                                throw new LockFail("Failed to obtain file lock within the specified timeout");
                            }
                            log.info("Lock file is already locked, waiting...");
                            Try.run(() -> Thread.sleep(Math.min(SLEEP, timeout)));
                            return acquire(Math.max(timeout - SLEEP, 0), lockName, callback);
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
