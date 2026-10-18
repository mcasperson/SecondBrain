package secondbrain.domain.persist;

import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.CosmosException;
import com.azure.cosmos.models.CosmosItemRequestOptions;
import com.azure.cosmos.models.PartitionKey;
import io.vavr.control.Try;

import java.util.Optional;

/**
 * Adapts the Cosmos SDK CosmosContainer to the CosmosCacheContainer interface.
 * Instances are created by {@link CosmosCacheContainerProducer}.
 */
public class CosmosCacheContainerLive implements CosmosCacheContainer {
    private static final int NOT_FOUND = 404;

    private final CosmosClient cosmosClient;
    private final CosmosContainer container;

    public CosmosCacheContainerLive(final CosmosClient cosmosClient, final CosmosContainer container) {
        this.cosmosClient = cosmosClient;
        this.container = container;
    }

    @Override
    public Optional<CosmosLocalStorage.CacheItem> readItem(final String id, final String partitionKey) {
        return Try.of(() -> container.readItem(id, new PartitionKey(partitionKey), CosmosLocalStorage.CacheItem.class))
                .map(response -> Optional.ofNullable(response.getItem()))
                // A missing item is reported as a 404 error, which is returned as an empty result
                .recoverWith(CosmosException.class, ex -> ex.getStatusCode() == NOT_FOUND
                        ? Try.success(Optional.empty())
                        : Try.failure(ex))
                .get();
    }

    @Override
    public void upsertItem(final CosmosLocalStorage.CacheItem item, final String partitionKey) {
        container.upsertItem(item, new PartitionKey(partitionKey), new CosmosItemRequestOptions());
    }

    @Override
    public void close() {
        cosmosClient.close();
    }
}
