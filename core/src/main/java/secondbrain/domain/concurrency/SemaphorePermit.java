package secondbrain.domain.concurrency;

import java.util.concurrent.Semaphore;

/**
 * Represents a permit against a shared semaphore that can be released using try-with-resources.
 */
public class SemaphorePermit implements AutoCloseable {
    private final Semaphore semaphore;
    private boolean acquired = false;

    public SemaphorePermit(final Semaphore semaphore) throws InterruptedException {
        this.semaphore = semaphore;
        acquire();
    }

    private void acquire() throws InterruptedException {
        semaphore.acquire();
        /*
            acquire might throw an exception, so we need to set acquired to
            true only after acquiring the semaphore
         */
        acquired = true;
    }

    private void release() {
        if (acquired) {
            semaphore.release();
            acquired = false;
        }
    }

    @Override
    public void close() {
        release();
    }
}
