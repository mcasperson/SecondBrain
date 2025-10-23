package secondbrain.domain.mutex;

import com.azure.core.util.MetricsOptions;
import com.azure.cosmos.*;
import com.azure.cosmos.implementation.NotFoundException;
import com.azure.cosmos.models.*;
import io.vavr.control.Try;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.exceptionhandling.ExceptionHandler;
import secondbrain.domain.exceptions.LocalStorageFailure;
import secondbrain.domain.exceptions.LockFail;
import secondbrain.domain.persist.CosmosLocalStorage;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * A Mutex implementation that uses Azure Cosmos DB's optimistic concurrency control
 * to implement distributed locking across multiple processes and machines.
 */
@ApplicationScoped
public class CosmosMutex implements Mutex {
    private static final int DEFAULT_TTL_SECONDS = 60 * 60 * 24;
    private static final long SLEEP_MS = 100;
    private final String ownerId = UUID.randomUUID().toString();

    @Inject
    @ConfigProperty(name = "sb.cosmos.endpoint")
    private Optional<String> cosmosEndpoint;

    @Inject
    @ConfigProperty(name = "sb.cosmos.key")
    private Optional<String> cosmosKey;

    @Inject
    @ConfigProperty(name = "sb.cosmos.lockdatabase", defaultValue = "secondbrainlock")
    private Optional<String> databaseName;

    @Inject
    @ConfigProperty(name = "sb.cosmos.container", defaultValue = "locks")
    private Optional<String> containerName;

    @Inject
    private ExceptionHandler exceptionHandler;

    @Inject
    private Logger logger;

    private CosmosClient cosmosClient;
    private CosmosContainer container;

    @PostConstruct
    public void postConstruct() {
        logger.info("Initializing Cosmos DB local storage");
        synchronized (CosmosLocalStorage.class) {
            if (cosmosClient == null) {
                Try.run(this::initializeCosmosClient)
                        .onFailure(ex -> logger.warning(exceptionHandler.getExceptionMessage(ex)));
            }
        }
        logger.info("Initialized Cosmos DB local storage");
    }

    @PreDestroy
    public void preDestroy() {
        synchronized (CosmosLocalStorage.class) {
            if (cosmosClient != null) {
                Try.run(cosmosClient::close)
                        .onFailure(ex -> logger.warning(exceptionHandler.getExceptionMessage(ex)));
                cosmosClient = null;
                container = null;
            }
        }
    }

    private void initializeCosmosClient() {
        if (cosmosEndpoint.isEmpty() || cosmosKey.isEmpty()) {
            throw new LocalStorageFailure("Cosmos DB endpoint and key must be configured");
        }

        final String databaseName = this.databaseName.orElse("secondbrainlock");
        final String containerName = this.containerName.orElse("locks");

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
                "/tool"
        );

        // Set TTL on the container to enable automatic deletion of expired items
        containerProperties.setDefaultTimeToLiveInSeconds(DEFAULT_TTL_SECONDS);

        Try.of(() -> database.createContainerIfNotExists(containerProperties))
                .onFailure(ex -> logger.warning("Failed to create container: " + exceptionHandler.getExceptionMessage(ex)));

        container = database.getContainer(containerName);
    }

    @Override
    public <T> T acquire(final long timeout, final String lockName, final MutexCallback<T> callback) {
        final long startTime = System.currentTimeMillis();

        while (true) {
            final Try<T> result = tryAcquireAndExecute(container, lockName, callback);

            if (result.isSuccess()) {
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

    private <T> Try<T> tryAcquireAndExecute(final CosmosContainer container, final String lockName, final MutexCallback<T> callback) {
        // We either
        final Try<String> etag = Try.of(() -> container.readItem(lockName, new PartitionKey(lockName), LockDocument.class).getItem())
                // We can proceed if the existing lock is stale
                .filter(LockDocument::isLockStale)
                // If the existing lock was not found, we create a new lock
                .recover(NotFoundException.class, ex -> new LockDocument(lockName, ownerId, Instant.now()))
                // NoSuchElementException means the lock exists and is not stale.
                // We failed to obtain the lock.
                .recover(NoSuchElementException.class, ex -> {
                    throw new LockFail("Failed to obtain lock", ex);
                })
                // get a new etag for a stale lock, or the first etag for a new lock
                .map(lockDoc -> container.upsertItem(
                        lockDoc,
                        new PartitionKey(lockName),
                        new CosmosItemRequestOptions().setIfMatchETag(lockDoc.eTag())))
                .map(CosmosItemResponse::getETag);

        // If we failed, return the failure
        if (etag.isFailure()) {
            return Try.failure(etag.getCause());
        }

        // If we succeeded, run the callback, and then clean up the lock
        return Try.of(callback::apply)
                .andFinally(() -> releaseLock(etag.get(), lockName));
    }

    private void releaseLock(final String etag, final String lockName) {
        Try.of(() -> new CosmosItemRequestOptions().setIfMatchETag(etag))
                .map(options -> container.deleteItem(lockName, new PartitionKey(lockName), options))
                .onFailure(ex -> logger.warning("Failed to release lock: " + lockName + " - " + ex.getMessage()));
    }


    /**
     * Represents a lock document stored in Cosmos DB
     */
    private record LockDocument(String id, String ownerId, Instant acquiredAt, String eTag) {
        public LockDocument() {
            this("", "", Instant.EPOCH, "");
        }

        public LockDocument(String id, String ownerId, Instant acquiredAt) {
            this(id, ownerId, acquiredAt, "");
        }

        public LockDocument updateETag(String newETag) {
            return new LockDocument(this.id, this.ownerId, this.acquiredAt, newETag);
        }

        public boolean isLockStale() {
            final Instant lockTime = acquiredAt();
            final Instant now = Instant.now();
            return lockTime.plusSeconds(DEFAULT_TTL_SECONDS).isBefore(now);
        }
    }
}

