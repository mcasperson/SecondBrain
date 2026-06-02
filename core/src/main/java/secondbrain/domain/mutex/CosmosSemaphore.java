package secondbrain.domain.mutex;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import secondbrain.domain.exceptions.LockFail;
import secondbrain.domain.mutex.config.MutexTimeout;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * A Semaphore implementation that uses a pool of CosmosMutex instances to allow
 * a limited number of concurrent executions across distributed processes.
 * <p>
 * Each semaphore slot is represented as a distinct lock name (lockName_0, lockName_1, etc.).
 * The implementation attempts to acquire any one of the available slots using tryAcquire.
 * If none can be obtained, it sleeps and retries until the timeout is reached.
 */
@ApplicationScoped
public class CosmosSemaphore implements Semaphore {

    private static final long SLEEP_MS = 1000;

    @Inject
    private CosmosMutex mutex;

    @Inject
    private MutexTimeout mutexTimeout;

    @Override
    public <T> T acquire(final int allowed, final long timeout, final String lockName, final MutexCallback<T> callback) {
        checkArgument(allowed > 0, "Allowed concurrency must be at least 1");

        final long startTime = System.nanoTime();

        while (true) {

            final List<Integer> randomizedRange = IntStream.range(0, allowed)
                    .boxed()
                    .collect(Collectors.toList());

            Collections.shuffle(randomizedRange);

            // Attempt to acquire any one of the available semaphore slots
            final Try<T> result = randomizedRange
                    .stream()
                    .map(i -> mutex.tryAcquire(lockName + "_" + i, callback))
                    .filter(Try::isSuccess)
                    .findFirst()
                    .orElseGet(() -> Try.failure(new LockFail("All semaphore slots are currently held for: " + lockName)));

            if (result.isSuccess()) {
                return result.get();
            }

            // If the result failed for a reason other than lock contention, propagate the error
            if (!(result.getCause() instanceof LockFail)) {
                return result.get(); // This will throw the underlying exception
            }

            final long elapsed = System.nanoTime() - startTime;
            if (elapsed >= timeout) {
                throw new LockFail("Failed to obtain semaphore slot within the specified timeout: " + timeout + "ms for lock: " + lockName);
            }

            // Sleep before retrying
            final long sleepMs = Math.min(SLEEP_MS, timeout - elapsed);
            Try.run(() -> Thread.sleep(sleepMs));
        }
    }

    @Override
    public <T> T acquire(final int allowed, final String lockName, final MutexCallback<T> callback) {
        return acquire(allowed, mutexTimeout.getDefaultTimeout(), lockName, callback);
    }
}


