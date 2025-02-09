package secondbrain.domain.concurrency;

import java.util.concurrent.Semaphore;

/**
 * Represents a permit against a shared semaphore that can be released using try-with-resources.
 */
public class SemaphorePermitWrapper<T extends AutoCloseable> implements AutoCloseable {
    private final Semaphore semaphore;
    private final T wrapped;
    private boolean acquired = false;

    public SemaphorePermitWrapper(final Semaphore semaphore, final T wrapped) throws InterruptedException {
        this.semaphore = semaphore;
        this.wrapped = wrapped;
        acquire();
    }

    public T getWrapped() {
        return wrapped;
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
    public void close() throws Exception {
        try {
            release();
        } finally {
            if (wrapped != null) {
                wrapped.close();
            }
        }
    }
}
