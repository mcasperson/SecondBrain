package secondbrain.domain.mutex;

import org.jspecify.annotations.Nullable;

import java.util.Optional;

/**
 * Exposes the Cosmos DB container operations used by {@link CosmosMutex} behind an interface.
 * The Cosmos SDK's CosmosContainer class has no interface, so this adapter allows it to be swapped for a test instance.
 */
public interface CosmosLockContainer {
    /**
     * Reads a lock document from the container.
     *
     * @param id           The id of the lock
     * @param partitionKey The partition key of the lock
     * @return The lock document, or empty if it does not exist
     */
    Optional<CosmosMutex.LockDocument> readItem(String id, String partitionKey);

    /**
     * Creates or replaces a lock document. If an etag is supplied, the operation fails if the
     * existing document has a different etag.
     *
     * @param item         The lock document to save
     * @param partitionKey The partition key of the lock
     * @param ifMatchEtag  The etag the existing document must match, or null to skip the check
     * @return The etag of the saved document
     */
    String upsertItem(CosmosMutex.LockDocument item, String partitionKey, @Nullable String ifMatchEtag);

    /**
     * Deletes a lock document if its etag matches. A document that no longer exists, or that has a different
     * etag, is ignored because the lock was already released or has expired.
     *
     * @param id           The id of the lock
     * @param partitionKey The partition key of the lock
     * @param ifMatchEtag  The etag the existing document must match
     */
    void deleteItem(String id, String partitionKey, String ifMatchEtag);

    /**
     * Deletes every lock document in the container. A missing database or container is ignored.
     *
     * @param partitionKey The partition key of the locks
     */
    void deleteAllItems(String partitionKey);

    /**
     * Releases any resources held by the container.
     */
    void close();
}
