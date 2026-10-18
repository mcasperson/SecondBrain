package secondbrain.domain.persist;

import java.util.Optional;

/**
 * Exposes the Cosmos DB container operations used by {@link CosmosLocalStorage} behind an interface.
 * The Cosmos SDK's CosmosContainer class has no interface, so this adapter allows it to be swapped for a test instance.
 */
public interface CosmosCacheContainer {
    /**
     * Reads an item from the container.
     *
     * @param id           The id of the item
     * @param partitionKey The partition key of the item
     * @return The item, or empty if it does not exist
     */
    Optional<CosmosLocalStorage.CacheItem> readItem(String id, String partitionKey);

    /**
     * Creates or replaces an item in the container.
     *
     * @param item         The item to save
     * @param partitionKey The partition key of the item
     */
    void upsertItem(CosmosLocalStorage.CacheItem item, String partitionKey);

    /**
     * Releases any resources held by the container.
     */
    void close();
}
