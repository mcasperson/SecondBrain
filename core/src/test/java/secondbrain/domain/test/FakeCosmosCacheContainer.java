package secondbrain.domain.test;

import org.jspecify.annotations.Nullable;
import secondbrain.domain.persist.CosmosCacheContainer;
import secondbrain.domain.persist.CosmosLocalStorage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An in-memory test instance of CosmosCacheContainer. Expose it to the class being tested with a @Produces method.
 */
public class FakeCosmosCacheContainer implements CosmosCacheContainer {
    private final Map<String, CosmosLocalStorage.CacheItem> items = new ConcurrentHashMap<>();
    private final List<CosmosLocalStorage.CacheItem> upserts = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger reads = new AtomicInteger();
    private final AtomicInteger closes = new AtomicInteger();
    @Nullable
    private volatile RuntimeException readFailure;

    /**
     * Adds an item as if it had been saved to the container earlier.
     */
    public void addItem(final CosmosLocalStorage.CacheItem item) {
        items.put(item.id(), item);
    }

    /**
     * Makes every subsequent read throw the supplied exception.
     */
    public void failReadsWith(final RuntimeException failure) {
        readFailure = failure;
    }

    /**
     * @return Every item passed to upsertItem, in the order the calls were made.
     */
    public List<CosmosLocalStorage.CacheItem> getUpserts() {
        synchronized (upserts) {
            return List.copyOf(upserts);
        }
    }

    public int getReadCount() {
        return reads.get();
    }

    public int getCloseCount() {
        return closes.get();
    }

    @Override
    public Optional<CosmosLocalStorage.CacheItem> readItem(final String id, final String partitionKey) {
        reads.incrementAndGet();
        final RuntimeException failure = readFailure;
        if (failure != null) {
            throw failure;
        }
        return Optional.ofNullable(items.get(id));
    }

    @Override
    public void upsertItem(final CosmosLocalStorage.CacheItem item, final String partitionKey) {
        upserts.add(item);
        items.put(item.id(), item);
    }

    @Override
    public void close() {
        closes.incrementAndGet();
    }
}
