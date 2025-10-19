package secondbrain.domain.concurrency;

import io.vavr.control.Try;

import java.util.concurrent.Semaphore;

/**
 * Represents a shared semaphore that can be lent out to multiple threads using try-with-resources.
 */
public class SemaphoreLender {
    private final Semaphore semaphore;

    public SemaphoreLender(int permits) {
        semaphore = new Semaphore(permits);
    }

    /**
     * Lend a permit from the semaphore. Use this when you have no other closable resource to manage
     * in a try-with-resources block.
     */
    public SemaphorePermit lend() throws InterruptedException {
        return new SemaphorePermit(semaphore);
    }

    /**
     * Lend a permit from the semaphore. Use this when you have no other closable resource to manage
     * in a try-with-resources block.
     */
    public Try.WithResources1<SemaphorePermit> lendAutoClose() {
        return Try.withResources(() -> new SemaphorePermit(semaphore));
    }

    /**
     * Lend a permit from the semaphore that wraps another closable resource. Use this when you have a
     * closable resource to manage in a try-with-resources block that must block on the semaphore.
     */
    public <T extends AutoCloseable> SemaphorePermitWrapper<T> lend(final T wrapped) throws InterruptedException {
        return new SemaphorePermitWrapper<>(semaphore, wrapped);
    }
}
