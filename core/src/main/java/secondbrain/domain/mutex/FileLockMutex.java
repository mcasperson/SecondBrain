package secondbrain.domain.mutex;

import io.vavr.API;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import secondbrain.domain.exceptions.LockFail;
import secondbrain.domain.tryext.TryExtensions;

import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/**
 * A Mutex implementation that uses file locks to ensure mutual exclusion across different JVMs.
 * This implementation is not reentrant. If you try to acquire the same lock in sequence,
 * it will result in a OverlappingFileLockException.
 */
@ApplicationScoped
public class FileLockMutex implements Mutex {
    private static final long SLEEP = 10000;
    private static final Map<String, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    @Inject
    private Logger log;

    @Override
    public <T> T acquire(final long timeout, final String lockName, final MutexCallback<T> callback) {
        final ReentrantLock localLock = LOCKS.computeIfAbsent(lockName, k -> new ReentrantLock());

        // We lock at the JVM level first to avoid multiple threads in the same JVM from trying to acquire the file lock simultaneously.
        return Try.of(() -> localLock.tryLock(timeout, TimeUnit.MILLISECONDS))
                // We only proceed if we acquired the local lock.
                .filter(acquired -> acquired)
                // If we fail to acquire the local lock, we throw a LockFail exception.
                .mapFailure(API.Case(API.$(), ex -> new LockFail("Failed to obtain file lock within the specified timeout", ex)))
                // If we acquired the local lock, we proceed to establish the file lock.
                .map(a -> establishFileLock(timeout, lockName, callback))
                // Ensure we always release the local lock if we acquired it.
                .andFinally(() -> {
                    if (localLock.isHeldByCurrentThread()) {
                        localLock.unlock();
                    }
                })
                .get();
    }

    public <T> T establishFileLock(final long timeout, final String lockName, final MutexCallback<T> callback) {
        long timeoutRemaining = timeout;

        while (true) {
            final Try<T> result = TryExtensions.withResources(
                            () -> new RandomAccessFile(lockName, "rw").getChannel(),
                            channel -> channel.tryLock(0, 0, false))
                    .of(lock -> callIfNotNull(lock, callback))
                    .onFailure(OverlappingFileLockException.class, ex -> {
                        log.severe("""
                                Lock file is already locked by this JVM (OverlappingFileLockException).
                                This implementation is not reentrant.
                                This exception usually means you attempted to get the lock again from the originally locked method.""".stripIndent());
                    });

            if (result.isSuccess()) {
                return result.get();
            }

            timeoutRemaining -= SLEEP;

            if (timeoutRemaining <= 0) {
                throw new LockFail("Failed to obtain file lock within the specified timeout");
            }

            Try.run(() -> Thread.sleep(Math.min(SLEEP, timeout)));
        }
    }

    private <T> T callIfNotNull(final FileLock lock, final MutexCallback<T> callback) {
        if (lock != null) {
            return callback.apply();
        }

        throw new LockFail("Failed to obtain file lock");
    }
}
