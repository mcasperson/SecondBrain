package secondbrain.domain.test;

import org.jspecify.annotations.Nullable;
import secondbrain.domain.exceptions.LockFail;
import secondbrain.domain.mutex.CosmosLockContainer;
import secondbrain.domain.mutex.CosmosMutex;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An in-memory test instance of CosmosLockContainer. Expose it to the class being tested with a @Produces method.
 * Every call is recorded, and tests can add existing locks or make reads and upserts fail.
 */
public class FakeCosmosLockContainer implements CosmosLockContainer {
    private final Map<String, CosmosMutex.LockDocument> items = new ConcurrentHashMap<>();
    private final Map<String, Integer> releaseAfterReads = new ConcurrentHashMap<>();
    private final List<String> reads = Collections.synchronizedList(new ArrayList<>());
    private final List<String> upserts = Collections.synchronizedList(new ArrayList<>());
    private final List<String> deletes = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger etags = new AtomicInteger();
    private final AtomicInteger deleteAllCount = new AtomicInteger();
    private final AtomicInteger closes = new AtomicInteger();
    @Nullable
    private volatile RuntimeException readFailure;
    @Nullable
    private volatile RuntimeException upsertFailure;

    /**
     * Adds a lock as if another process had acquired it at the supplied time.
     */
    public void addLock(final String id, final Instant acquiredAt) {
        items.put(id, new CosmosMutex.LockDocument(id, "lock", acquiredAt, nextEtag(), 300));
    }

    /**
     * Removes the lock after it has been read the supplied number of times, as if the other process released it.
     */
    public void releaseLockAfterReads(final String id, final int count) {
        releaseAfterReads.put(id, count);
    }

    /**
     * Makes every subsequent read throw the supplied exception.
     */
    public void failReadsWith(final RuntimeException failure) {
        readFailure = failure;
    }

    /**
     * Makes every subsequent upsert throw the supplied exception.
     */
    public void failUpsertsWith(final RuntimeException failure) {
        upsertFailure = failure;
    }

    public boolean hasLock(final String id) {
        return items.containsKey(id);
    }

    /**
     * @return The id of every read, in the order the calls were made.
     */
    public List<String> getReads() {
        synchronized (reads) {
            return List.copyOf(reads);
        }
    }

    /**
     * @return The id of every successful upsert, in the order the calls were made.
     */
    public List<String> getUpserts() {
        synchronized (upserts) {
            return List.copyOf(upserts);
        }
    }

    /**
     * @return The id of every delete, in the order the calls were made.
     */
    public List<String> getDeletes() {
        synchronized (deletes) {
            return List.copyOf(deletes);
        }
    }

    public int getDeleteAllCount() {
        return deleteAllCount.get();
    }

    public int getCloseCount() {
        return closes.get();
    }

    @Override
    public Optional<CosmosMutex.LockDocument> readItem(final String id, final String partitionKey) {
        reads.add(id);
        final RuntimeException failure = readFailure;
        if (failure != null) {
            throw failure;
        }

        final Optional<CosmosMutex.LockDocument> item = Optional.ofNullable(items.get(id));
        releaseAfterReads.computeIfPresent(id, (key, remaining) -> {
            if (remaining <= 1) {
                items.remove(key);
                return null;
            }
            return remaining - 1;
        });
        return item;
    }

    @Override
    public String upsertItem(final CosmosMutex.LockDocument item, final String partitionKey, @Nullable final String ifMatchEtag) {
        final RuntimeException failure = upsertFailure;
        if (failure != null) {
            throw failure;
        }

        final CosmosMutex.LockDocument existing = items.get(item.id());
        if (ifMatchEtag != null && (existing == null || !Objects.equals(existing.eTag(), ifMatchEtag))) {
            // Matches CosmosLockContainerLive, which reports an etag mismatch as lock contention
			throw new LockFail("Failed to obtain lock - lock " + item.id() + " was saved by another process");
        }

        final String etag = nextEtag();
        items.put(item.id(), new CosmosMutex.LockDocument(item.id(), item.lock(), item.acquiredAt(), etag, item.ttl()));
        upserts.add(item.id());
        return etag;
    }

    @Override
    public void deleteItem(final String id, final String partitionKey, final String ifMatchEtag) {
        deletes.add(id);
        items.computeIfPresent(id, (key, existing) -> Objects.equals(existing.eTag(), ifMatchEtag) ? null : existing);
    }

    @Override
    public void deleteAllItems(final String partitionKey) {
        deleteAllCount.incrementAndGet();
        items.clear();
    }

    @Override
    public void close() {
        closes.incrementAndGet();
    }

    private String nextEtag() {
        return "etag-" + etags.incrementAndGet();
    }
}
