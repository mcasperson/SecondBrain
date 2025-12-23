package secondbrain.domain.mutex;

import com.azure.core.util.MetricsOptions;
import com.azure.cosmos.*;
import com.azure.cosmos.implementation.NotFoundException;
import com.azure.cosmos.models.*;
import com.azure.cosmos.util.CosmosPagedIterable;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.vavr.control.Try;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.exceptionhandling.ExceptionHandler;
import secondbrain.domain.exceptions.LocalStorageFailure;
import secondbrain.domain.exceptions.LockFail;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * A Mutex implementation that uses Azure Cosmos DB's optimistic concurrency control
 * to implement distributed locking across multiple processes and machines.
 */
@ApplicationScoped
public class CosmosMutex implements Mutex {

    private static final String DATABASE_NAME = "secondbrainlock";
    private static final String CONTAINER_NAME = "locks";
    private static final String LOCK_PARTITION_KEY = "/lock";
    private static final String LOCK_PARTITION_VALUE = "lock";
    private static final int DEFAULT_TTL = 60 * 5;
    private static final long SLEEP_MS = 1000;

    @Inject
    @ConfigProperty(name = "sb.cosmos.endpoint")
    private Optional<String> cosmosEndpoint;

    @Inject
    @ConfigProperty(name = "sb.cosmos.key")
    private Optional<String> cosmosKey;

    @Inject
    @ConfigProperty(name = "sb.cosmos.lockttl", defaultValue = DEFAULT_TTL + "")
    private Optional<String> lockTtl;

    @Inject
    @ConfigProperty(name = "sb.cosmos.clearlocksonstartup", defaultValue = "false")
    private Optional<Boolean> clearOnStartup;

    /**
     * The default TTL represents how long a lock can be held. Either the lock becomes stale,
     * or Cosmos DB automatically deletes it.
     */
    @Inject
    @ConfigProperty(name = "sb.cosmos.lockdatabase", defaultValue = DATABASE_NAME)
    private Optional<String> databaseName;

    @Inject
    @ConfigProperty(name = "sb.cosmos.lockscontainer", defaultValue = CONTAINER_NAME)
    private Optional<String> containerName;

    @Inject
    private ExceptionHandler exceptionHandler;

    @Inject
    private Logger logger;

    @Nullable
    private CosmosClient cosmosClient;

    @Nullable
    private CosmosContainer container;

    private int getLockTtlSeconds() {
        return Try.of(() -> Integer.parseInt(lockTtl.get())).getOrElse(DEFAULT_TTL);
    }

    @PostConstruct
    public void postConstruct() {
        logger.fine("Initializing Cosmos DB local storage");
        synchronized (CosmosMutex.class) {
            if (cosmosClient == null) {
                Try.run(this::initializeCosmosClient)
                        .onFailure(ex -> logger.warning(exceptionHandler.getExceptionMessage(ex)));
            }
        }
        logger.fine("Initialized Cosmos DB local storage");

        clearLocksOnStartup();
    }

    @PreDestroy
    public void preDestroy() {
        synchronized (CosmosMutex.class) {
            if (cosmosClient != null) {
                Try.run(cosmosClient::close)
                        .onFailure(ex -> logger.warning(exceptionHandler.getExceptionMessage(ex)));
                cosmosClient = null;
                container = null;
            }
        }
    }

    private void clearLocksOnStartup() {
        if (!clearOnStartup.orElse(false)) {
            return;
        }

        if (cosmosClient == null) {
            logger.warning("Cosmos client is not initialized, cannot clear locks on startup");
            return;
        }

        final String query = "SELECT * FROM c";
        final String databaseName = this.databaseName.orElse(DATABASE_NAME);
        final String containerName = this.containerName.orElse(CONTAINER_NAME);

        logger.fine("Clearing all locks from Cosmos DB on startup");
        Try.of(() -> cosmosClient.getDatabase(databaseName))
                .map(database -> database.getContainer(containerName))
                .map(container -> deleteFiles(container, container.queryItems(query, new CosmosQueryRequestOptions(), LockDocument.class)))
                .onFailure(ex -> {
                    if (!(ex instanceof NotFoundException)) {
                        logger.warning("Failed to clear locks on startup: " + exceptionHandler.getExceptionMessage(ex));
                    }
                });
    }

    private List<CosmosItemResponse<Object>> deleteFiles(final CosmosContainer container, final CosmosPagedIterable<LockDocument> lockDocuments) {
        return lockDocuments.stream()
                .map(doc -> container.deleteItem(doc.id(), new PartitionKey(LOCK_PARTITION_VALUE), new CosmosItemRequestOptions()))
                .toList();
    }

    private void initializeCosmosClient() {
        if (cosmosEndpoint.isEmpty() || cosmosKey.isEmpty()) {
            throw new LocalStorageFailure("Cosmos DB endpoint and key must be configured");
        }

        final String databaseName = this.databaseName.orElse(DATABASE_NAME);
        final String containerName = this.containerName.orElse(CONTAINER_NAME);

        final MetricsOptions metricsOptions = new CosmosMicrometerMetricsOptions();
        metricsOptions.setEnabled(false);

        final CosmosClientTelemetryConfig telemetryOptions = new CosmosClientTelemetryConfig();
        telemetryOptions.metricsOptions(metricsOptions);

        cosmosClient = new CosmosClientBuilder()
                .endpoint(cosmosEndpoint.get())
                .key(cosmosKey.get())
                .consistencyLevel(ConsistencyLevel.SESSION)
                .clientTelemetryConfig(telemetryOptions)
                .buildClient();

        cosmosClient.createDatabaseIfNotExists(databaseName);

        final CosmosDatabase database = cosmosClient.getDatabase(databaseName);

        // Create container if it doesn't exist
        final CosmosContainerProperties containerProperties = new CosmosContainerProperties(
                containerName,
                LOCK_PARTITION_KEY
        );

        // Set TTL on the container to enable automatic deletion of expired items
        containerProperties.setDefaultTimeToLiveInSeconds(getLockTtlSeconds());

        Try.of(() -> database.createContainerIfNotExists(containerProperties))
                .onFailure(ex -> logger.warning("Failed to create container: " + exceptionHandler.getExceptionMessage(ex)));

        container = database.getContainer(containerName);
    }

    @Override
    public <T> T acquire(final long timeout, final String lockName, final MutexCallback<T> callback) {
        final long startTime = System.currentTimeMillis();

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
            Try.run(() -> Thread.sleep(Math.min(SLEEP_MS, timeout - elapsed)));
        }
    }

    private <T> Try<T> tryAcquireAndExecute(@Nullable final CosmosContainer container, final String lockName, final MutexCallback<T> callback) {
        if (container == null) {
            return Try.failure(new LocalStorageFailure("Cosmos DB client is not initialized"));
        }

        final Try<String> etag = Try.of(() -> container.readItem(lockName, new PartitionKey(LOCK_PARTITION_VALUE), LockDocument.class).getItem())
                // If there are any cosmosdb errors, we want to log them
                .onFailure(ex -> logReadItemFailure(lockName, ex))
                // We can proceed if the existing lock is stale
                .filter(lockDoc -> lockDoc.isLockStale(getLockTtlSeconds()))
                // If the existing lock was not found, we create a new lock
                .recover(NotFoundException.class, ex -> new LockDocument(lockName, LOCK_PARTITION_VALUE, Instant.now(), getLockTtlSeconds()))
                // NoSuchElementException means the lock exists and is not stale.
                // We failed to obtain the lock.
                .recover(NoSuchElementException.class, ex -> {
                    throw new LockFail("Failed to obtain lock - lock " + lockName + " is currently held", ex);
                })
                // Get a new etag for a stale lock, or the first etag for a new lock.
                // If we have two processes trying to acquire the same lock at the same time,
                // one of them will fail here due to the etag mismatch.
                .map(lockDoc -> container.upsertItem(
                        lockDoc,
                        new PartitionKey(LOCK_PARTITION_VALUE),
                        new CosmosItemRequestOptions().setIfMatchETag(lockDoc.getFixedEtag())))
                .map(CosmosItemResponse::getETag);

        // If we failed, return the failure
        if (etag.isFailure()) {
            return Try.failure(etag.getCause());
        }

        // If we succeeded, run the callback, and then clean up the lock
        return Try.of(callback::apply)
                .andFinally(() -> releaseLock(etag.get(), lockName));
    }

    private void logReadItemFailure(final String lockName, final Throwable ex) {
        // We ignore not found exceptions as they are expected when the lock does not exist
        if (!(ex instanceof NotFoundException)) {
            logger.warning("Failed to read lock item: " + lockName + " - " + exceptionHandler.getExceptionMessage(ex));
        }
    }

    private void releaseLock(final String etag, final String lockName) {
        if (container == null) {
            logger.warning("Cosmos DB client is not initialized, cannot release lock: " + lockName);
            return;
        }

        Try.of(() -> new CosmosItemRequestOptions().setIfMatchETag(etag))
                .map(options -> container.deleteItem(lockName, new PartitionKey(LOCK_PARTITION_VALUE), options))
                /*
                    There are a number of reasons for this to fail:
                    1. The lock was already deleted (e.g. due to TTL expiry)
                    2. The lock expired due to TTL expiry, was deleted by Cosmos DB, and recreated by another process so there is a new etag

                    We don't fail the process of releasing a lock, as the locked operation has already completed successfully.
                 */
                .onFailure(ex -> {
                    final boolean isNotFound = ex instanceof NotFoundException;
                    final boolean isPreconditionFailed = ex instanceof CosmosException cosmosEx && cosmosEx.getStatusCode() == 412;

                    // We ignore errors when the lock file was deleted due to TTL expiry
                    if (!isNotFound && !isPreconditionFailed) {
                        logger.warning("Failed to release lock: " + lockName + " - " + ex.getMessage());
                    }
                });
    }


    /**
     * Represents a lock document stored in Cosmos DB
     */
    private record LockDocument(String id, String lock, Instant acquiredAt,
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

