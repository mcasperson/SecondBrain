package secondbrain.domain.mutex;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.vavr.control.Try;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Startup;
import jakarta.inject.Inject;
import org.apache.commons.lang.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.exceptionhandling.ExceptionHandler;
import secondbrain.domain.exceptions.LocalStorageFailure;
import secondbrain.domain.exceptions.LockFail;
import secondbrain.domain.mutex.config.MutexTimeout;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/**
 * A Mutex implementation that uses Azure Cosmos DB's optimistic concurrency control
 * to implement distributed locking across multiple processes and machines.
 * The lock is not reentrant.
 */
@ApplicationScoped
public class CosmosMutex implements Mutex {

    static final int DEFAULT_TTL = 60 * 5;
    private static final String LOCK_PARTITION_VALUE = "lock";
    private static final long SLEEP_MS = 1000;

    /**
     * The default TTL represents how long a lock can be held. Either the lock becomes stale,
     * or Cosmos DB automatically deletes it.
     */
    @Inject
    @ConfigProperty(name = "sb.cosmos.lockttl", defaultValue = DEFAULT_TTL + "")
    private Optional<String> lockTtl;

    @Inject
    @ConfigProperty(name = "sb.cosmos.clearlocksonstartup", defaultValue = "false")
    private Optional<Boolean> clearOnStartup;

    @Inject
    private ExceptionHandler exceptionHandler;

    @Inject
    private Logger logger;

    /**
     * The container is created by {@link CosmosLockContainerProducer}, or by a test producer.
     */
    @Inject
    private CosmosLockContainer cosmosLockContainer;

    @Nullable
    private CosmosLockContainer container;

    @Inject
    private MutexTimeout mutexTimeout;

    private final AtomicReference<Long> totalWaitMs = new AtomicReference<>(0L);
    private final ConcurrentMap<String, ReentrantLock> localMutexes = new ConcurrentHashMap<>();

    static int parseLockTtlSeconds(final Optional<String> lockTtl) {
        return Try.of(() -> Integer.parseInt(lockTtl.get())).getOrElse(DEFAULT_TTL);
    }

    private int getLockTtlSeconds() {
        return parseLockTtlSeconds(lockTtl);
    }

    // This observer forces the container to instantiate the bean at startup
    public void onStartup(@Observes final Startup event) {
        // Initialization logic here
    }

    @PostConstruct
    public void postConstruct() {
        logger.fine("Initializing Cosmos DB mutex");
        container = cosmosLockContainer;
        logger.fine("Initialized Cosmos DB mutex");

        clearLocksOnStartup();
    }

    private String formatWaitTime(final long ms) {
        if (ms >= 3_600_000) {
            final long hours = ms / 3_600_000;
            final long minutes = (ms % 3_600_000) / 60_000;
            final long seconds = (ms % 60_000) / 1000;
            return String.format("%dh %dm %ds (%,dms)", hours, minutes, seconds, ms);
        } else if (ms >= 60_000) {
            final long minutes = ms / 60_000;
            final long seconds = (ms % 60_000) / 1000;
            return String.format("%dm %ds (%,dms)", minutes, seconds, ms);
        } else {
            return String.format("%.2fs (%,dms)", ms / 1000.0, ms);
        }
    }

    @PreDestroy
    public void preDestroy() {
        final long waited = Objects.requireNonNullElse(totalWaitMs.get(), 0L);
        logger.info("Total time spent waiting to acquire Cosmos locks: " + formatWaitTime(waited));

        // The container itself is closed by the producer's disposer method
        container = null;
    }

    private void clearLocksOnStartup() {
        if (!clearOnStartup.orElse(false)) {
            return;
        }

        final CosmosLockContainer container = this.container;
        if (container == null) {
            logger.warning("Cosmos client is not initialized, cannot clear locks on startup");
            return;
        }

        logger.fine("Clearing all locks from Cosmos DB on startup");
        Try.run(() -> container.deleteAllItems(LOCK_PARTITION_VALUE))
                .onFailure(ex -> logger.warning("Failed to clear locks on startup: " + exceptionHandler.getExceptionMessage(ex)));
    }

    @Override
    public <T> T acquire(final long timeout, final String lockName, final MutexCallback<T> callback) {
        final long startTime = System.currentTimeMillis();
        final ReentrantLock localMutex = localMutexes.computeIfAbsent(lockName, ignored -> new ReentrantLock());
        boolean localLocked = false;

        try {
            localLocked = localMutex.tryLock(timeout, TimeUnit.MILLISECONDS);
            if (!localLocked) {
                throw new LockFail("Failed to obtain local lock within the specified timeout: " + timeout + "ms");
            }

            while (true) {
                final Try<T> result = tryAcquireAndExecute(container, lockName, callback);

                // Get the result if we succeeded, or throw if we failed for any reason other than lock contention
                if (result.isSuccess() || !(result.getCause() instanceof LockFail)) {
                    return result.get();
                }

                final long elapsed = System.currentTimeMillis() - startTime;
                if (elapsed >= timeout) {
                    throw new LockFail("Failed to obtain Cosmos lock within the specified timeout: " + timeout + "ms");
                }

                // Sleep before retrying
                final long sleepMs = Math.min(SLEEP_MS, timeout - elapsed);
                Try.run(() -> Thread.sleep(sleepMs));
                totalWaitMs.accumulateAndGet(sleepMs, Long::sum);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new LockFail("Interrupted while waiting to obtain local lock", ex);
        } finally {
            if (localLocked) {
                localMutex.unlock();
                // Best-effort cleanup to avoid map growth for lock names that are no longer used.
                if (!localMutex.isLocked() && !localMutex.hasQueuedThreads()) {
                    localMutexes.remove(lockName, localMutex);
                }
            }
        }
    }

    @Override
    public <T> T acquire(final String lockName, final MutexCallback<T> callback) {
        return acquire(mutexTimeout.getDefaultTimeout(), lockName, callback);
    }

    @Override
    public <T> Try<T> tryAcquire(final String lockName, final MutexCallback<T> callback) {
        final ReentrantLock localMutex = localMutexes.computeIfAbsent(lockName, ignored -> new ReentrantLock());

        if (!localMutex.tryLock()) {
            return Try.failure(new LockFail("Failed to obtain local lock for: " + lockName));
        }

        try {
            return tryAcquireAndExecute(container, lockName, callback);
        } finally {
            localMutex.unlock();
        }
    }

    private <T> Try<T> tryAcquireAndExecute(@Nullable final CosmosLockContainer container, final String lockName, final MutexCallback<T> callback) {
        if (container == null) {
            return Try.failure(new LocalStorageFailure("Cosmos DB client is not initialized"));
        }

        final Try<String> etag = Try.of(() -> container.readItem(lockName, LOCK_PARTITION_VALUE))
                // If there are any cosmosdb errors, we want to log them
                .onFailure(ex -> logReadItemFailure(lockName, ex))
                .flatMap(existing -> existing
                        // We can proceed if the existing lock is stale.
                        // If the lock exists and is not stale, we failed to obtain the lock.
                        .map(lockDoc -> lockDoc.isLockStale(getLockTtlSeconds())
                                ? Try.success(lockDoc)
                                : Try.<LockDocument>failure(new LockFail(
                                "Failed to obtain lock - lock " + lockName + " is currently held",
                                new NoSuchElementException("Lock " + lockName + " is not stale"))))
                        // If the existing lock was not found, we create a new lock
                        .orElseGet(() -> Try.success(new LockDocument(lockName, LOCK_PARTITION_VALUE, Instant.now(), getLockTtlSeconds()))))
                // Get a new etag for a stale lock, or the first etag for a new lock.
                // If we have two processes trying to acquire the same lock at the same time,
                // one of them will fail here due to the etag mismatch.
                .map(lockDoc -> container.upsertItem(lockDoc, LOCK_PARTITION_VALUE, lockDoc.getFixedEtag()));

        // If we failed, return the failure
        if (etag.isFailure()) {
            return Try.failure(etag.getCause());
        }

        // If we succeeded, run the callback, and then clean up the lock.
        // We assume the callback will succeed within the TTL of the lock, but this is not enforced.
        return Try.of(callback::apply)
                .andFinally(() -> releaseLock(etag.get(), lockName));
    }

    private void logReadItemFailure(final String lockName, final Throwable ex) {
        // Missing locks are returned as an empty result rather than an exception, so any exception is unexpected
        logger.warning("Failed to read lock item: " + lockName + " - " + exceptionHandler.getExceptionMessage(ex));
    }

    private void releaseLock(final String etag, final String lockName) {
        final CosmosLockContainer container = this.container;
        if (container == null) {
            logger.warning("Cosmos DB client is not initialized, cannot release lock: " + lockName);
            return;
        }

        /*
            There are a number of reasons for this to fail:
            1. The lock was already deleted (e.g. due to TTL expiry)
            2. The lock expired due to TTL expiry, was deleted by Cosmos DB, and recreated by another process, so there is a new etag

            The container ignores both of these cases. We don't fail the process of releasing a lock for any other
            error either, as the locked operation has already completed successfully.
         */
        Try.run(() -> container.deleteItem(lockName, LOCK_PARTITION_VALUE, etag))
                .onFailure(ex -> logger.warning("Failed to release lock: " + lockName + " - " + ex.getMessage()));
    }


    /**
     * Represents a lock document stored in Cosmos DB
     */
    public record LockDocument(String id, String lock, Instant acquiredAt,
                               @Nullable @JsonProperty("_etag") String eTag, Integer ttl) {

        private LockDocument(String id, String lock, Instant acquiredAt, Integer ttl) {
            this(id, lock, acquiredAt, null, ttl);
        }

        @Nullable
        private String getFixedEtag() {
            if (StringUtils.isBlank(eTag)) {
                return null;
            }

            return eTag;
        }

        private boolean isLockStale(int ttlSeconds) {
            final Instant lockTime = acquiredAt();
            final Instant now = Instant.now();
            return lockTime.plusSeconds(ttlSeconds).isBefore(now);
        }
    }
}

