package secondbrain.domain.concurrency;

import java.util.concurrent.Semaphore;

/**
 * Represents a shared semaphore that can be lent out to multiple threads using try-with-resources.
 */
public class SemaphoreLender {
    private final Semaphore semaphore;

    public SemaphoreLender(int permits) {
        semaphore = new Semaphore(permits);
    }

    public SemaphorePermit lend() throws InterruptedException {
        return new SemaphorePermit(semaphore);
    }

    public <T extends AutoCloseable> SemaphorePermitWrapper<T> lend(final T wrapped) throws InterruptedException {
        return new SemaphorePermitWrapper<>(semaphore, wrapped);
    }
}
