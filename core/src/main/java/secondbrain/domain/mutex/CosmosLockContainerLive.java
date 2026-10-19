package secondbrain.domain.mutex;

import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.CosmosException;
import com.azure.cosmos.implementation.NotFoundException;
import com.azure.cosmos.models.CosmosItemRequestOptions;
import com.azure.cosmos.models.CosmosQueryRequestOptions;
import com.azure.cosmos.models.PartitionKey;
import io.vavr.control.Try;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.exceptions.LockFail;

import java.util.Optional;

/**
 * Adapts the Cosmos SDK CosmosContainer to the CosmosLockContainer interface.
 * Instances are created by {@link CosmosLockContainerProducer}.
 */
public class CosmosLockContainerLive implements CosmosLockContainer {
    private static final int NOT_FOUND = 404;
    private static final int CONFLICT = 409;
    private static final int PRECONDITION_FAILED = 412;

    private final CosmosClient cosmosClient;
    private final CosmosContainer container;

    public CosmosLockContainerLive(final CosmosClient cosmosClient, final CosmosContainer container) {
        this.cosmosClient = cosmosClient;
        this.container = container;
    }

    @Override
    public Optional<CosmosMutex.LockDocument> readItem(final String id, final String partitionKey) {
        return Try.of(() -> container.readItem(id, new PartitionKey(partitionKey), CosmosMutex.LockDocument.class))
                .map(response -> Optional.ofNullable(response.getItem()))
                // A missing lock is reported as a 404 error, which is returned as an empty result
                .recoverWith(CosmosException.class, ex -> ex.getStatusCode() == NOT_FOUND
                        ? Try.success(Optional.empty())
                        : Try.failure(ex))
                .get();
    }

    @Override
    public String upsertItem(final CosmosMutex.LockDocument item, final String partitionKey, @Nullable final String ifMatchEtag) {
        return Try.of(() -> container.upsertItem(
                                item,
                                new PartitionKey(partitionKey),
                                new CosmosItemRequestOptions().setIfMatchETag(ifMatchEtag))
                        .getETag())
                // Another process saved the lock first, so this is lock contention rather than an error
                .recoverWith(CosmosException.class, ex -> ex.getStatusCode() == CONFLICT || ex.getStatusCode() == PRECONDITION_FAILED
                        ? Try.failure(new LockFail("Failed to obtain lock - lock " + item.id() + " was saved by another process", ex))
                        : Try.failure(ex))
                .get();
    }

    @Override
    public void deleteItem(final String id, final String partitionKey, final String ifMatchEtag) {
        Try.run(() -> container.deleteItem(id, new PartitionKey(partitionKey), new CosmosItemRequestOptions().setIfMatchETag(ifMatchEtag)))
                // The lock was already deleted (e.g. due to TTL expiry), or was recreated by another process with a new etag
                .recoverWith(CosmosException.class, ex -> ex.getStatusCode() == NOT_FOUND || ex.getStatusCode() == PRECONDITION_FAILED
                        ? Try.success(null)
                        : Try.failure(ex))
                .get();
    }

    @Override
    public void deleteAllItems(final String partitionKey) {
        Try.run(() -> container.queryItems("SELECT * FROM c", new CosmosQueryRequestOptions(), CosmosMutex.LockDocument.class)
                        .stream()
                        .forEach(doc -> container.deleteItem(doc.id(), new PartitionKey(partitionKey), new CosmosItemRequestOptions())))
                // A missing database or container means there are no locks to clear
                .recoverWith(NotFoundException.class, ex -> Try.success(null))
                .get();
    }

    @Override
    public void close() {
        cosmosClient.close();
    }
}
