package secondbrain.domain.test;

import secondbrain.domain.persist.CacheResult;
import secondbrain.domain.persist.GenerateValue;
import secondbrain.domain.persist.MockLocalStorage;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A cache that always misses (calling the generator) unless a value has been primed for a source.
 * Every lookup is recorded so tests can assert the cache keys and TTLs used by a client.
 */
public class RecordingLocalStorage extends MockLocalStorage {
    public static final long NO_TTL = Long.MIN_VALUE;

    public record Call(String tool, String source, String promptHash, long ttlSeconds) {
    }

    public final List<Call> calls = new CopyOnWriteArrayList<>();
    private final Map<String, Object> primed = new HashMap<>();
    private final Set<String> primedNull = new HashSet<>();

    /**
     * Return this value (as a cache hit) for any lookup against the source instead of calling the generator.
     */
    public RecordingLocalStorage prime(final String source, final Object value) {
        primed.put(source, value);
        return this;
    }

    /**
     * Return a null cached value for any lookup against the source.
     */
    public RecordingLocalStorage primeNull(final String source) {
        primedNull.add(source);
        return this;
    }

    public List<Call> callsFor(final String source) {
        return calls.stream().filter(c -> c.source().equals(source)).toList();
    }

    @SuppressWarnings("unchecked")
    private <T> CacheResult<T> lookup(final String tool, final String source, final String hash, final long ttl, final GenerateValue<T> generateValue) {
        calls.add(new Call(tool, source, hash, ttl));
        if (primedNull.contains(source)) {
            return new CacheResult<>(null, null, true);
        }
        if (primed.containsKey(source)) {
            return new CacheResult<>((T) primed.get(source), null, true);
        }
        return new CacheResult<>(generateValue.generate(), null, false);
    }

    @Override
    public CacheResult<String> getOrPutString(final String tool, final String source, final String promptHash, final long ttlSeconds, final GenerateValue<String> generateValue) {
        return lookup(tool, source, promptHash, ttlSeconds, generateValue);
    }

    @Override
    public CacheResult<String> getOrPutString(final String tool, final String source, final String promptHash, final GenerateValue<String> generateValue) {
        return lookup(tool, source, promptHash, NO_TTL, generateValue);
    }

    @Override
    public <T> CacheResult<T> getOrPutObject(final String tool, final String source, final String promptHash, final long ttlSeconds, final Class<T> clazz, final GenerateValue<T> generateValue) {
        return lookup(tool, source, promptHash, ttlSeconds, generateValue);
    }

    @Override
    public <T> CacheResult<T> getOrPutObject(final String tool, final String source, final String promptHash, final Class<T> clazz, final GenerateValue<T> generateValue) {
        return lookup(tool, source, promptHash, NO_TTL, generateValue);
    }

    @Override
    public <T> CacheResult<List<T>> getOrPutList(final String tool, final String source, final String promptHash, final long ttlSeconds, final Class<T> clazz, final GenerateValue<List<T>> generateValue) {
        return lookup(tool, source, promptHash, ttlSeconds, generateValue);
    }

    @Override
    public <T> CacheResult<List<T>> getOrPutList(final String tool, final String source, final String promptHash, final Class<T> clazz, final GenerateValue<List<T>> generateValue) {
        return lookup(tool, source, promptHash, NO_TTL, generateValue);
    }

    @Override
    public <T> CacheResult<T[]> getOrPutObjectArray(final String tool, final String source, final String promptHash, final long ttlSeconds, final Class<T> clazz, final Class<T[]> arrayClazz, final GenerateValue<T[]> generateValue) {
        return lookup(tool, source, promptHash, ttlSeconds, generateValue);
    }

    @Override
    public <T, U> CacheResult<T> getOrPutGeneric(final String tool, final String source, final String promptHash, final long ttlSeconds, final Class<T> container, final Class<U> contained, final GenerateValue<T> generateValue) {
        return lookup(tool, source, promptHash, ttlSeconds, generateValue);
    }

    @Override
    public <T, U> CacheResult<T> getOrPutGeneric(final String tool, final String source, final String promptHash, final Class<T> container, final Class<U> contained, final GenerateValue<T> generateValue) {
        return lookup(tool, source, promptHash, NO_TTL, generateValue);
    }

    @Override
    public <T, U, V> CacheResult<T> getOrPutGeneric(final String tool, final String source, final String promptHash, final long ttlSeconds, final Class<T> container, final Class<U> contained, final Class<V> contained2, final GenerateValue<T> generateValue) {
        return lookup(tool, source, promptHash, ttlSeconds, generateValue);
    }

    @Override
    public <T, U, V> CacheResult<T> getOrPutGeneric(final String tool, final String source, final String promptHash, final Class<T> container, final Class<U> contained, final Class<V> contained2, final GenerateValue<T> generateValue) {
        return lookup(tool, source, promptHash, NO_TTL, generateValue);
    }
}
