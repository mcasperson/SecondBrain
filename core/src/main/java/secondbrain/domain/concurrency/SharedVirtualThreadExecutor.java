package secondbrain.domain.concurrency;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A CDI application-scoped bean that exposes a shared virtual-thread-per-task executor.
 * Using a single shared instance avoids the overhead of creating and tearing down a new
 * ExecutorService on every call, and prevents spurious {@link InterruptedException}s that
 * can occur when {@code Try.withResources} closes the executor while tasks are still finishing.
 */
@ApplicationScoped
public class SharedVirtualThreadExecutor {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Returns the shared {@link ExecutorService} backed by virtual threads.
     * Callers must <em>not</em> shut down or close the returned executor.
     */
    public ExecutorService getExecutor() {
        return executor;
    }

    @PreDestroy
    void shutdown() {
        executor.close();
    }
}

