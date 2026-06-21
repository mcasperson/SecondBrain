package secondbrain.domain.persist;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * A no-op {@link LocalStorage} implementation that always returns cache misses.
 * Useful in tests to bypass the real caching layer.
 */
@ApplicationScoped
public class MockLocalStorage implements LocalStorage {

    @Override
    public CacheResult<String> getString(String tool, String source, String promptHash) {
        return new CacheResult<>(null, null, false);
    }

    @Override
    public CacheResult<String> getOrPutString(String tool, String source, String promptHash, long ttlSeconds, GenerateValue<String> generateValue) {
        return new CacheResult<>(generateValue.generate(), null, false);
    }

    @Override
    public CacheResult<String> getOrPutString(String tool, String source, String promptHash, GenerateValue<String> generateValue) {
        return getOrPutString(tool, source, promptHash, 0, generateValue);
    }

    @Override
    public <T> CacheResult<T> getOrPutObject(String tool, String source, String promptHash, long ttlSeconds, Class<T> clazz, GenerateValue<T> generateValue) {
        return new CacheResult<>(generateValue.generate(), null, false);
    }

    @Override
    public <T> CacheResult<T> getOrPutObject(String tool, String source, String promptHash, Class<T> clazz, GenerateValue<T> generateValue) {
        return getOrPutObject(tool, source, promptHash, 0, clazz, generateValue);
    }

    @Override
    public <T> CacheResult<List<T>> getOrPutList(String tool, String source, String promptHash, long ttlSeconds, Class<T> clazz, GenerateValue<List<T>> generateValue) {
        return new CacheResult<>(generateValue.generate(), null, false);
    }

    @Override
    public <T> CacheResult<List<T>> getOrPutList(String tool, String source, String promptHash, Class<T> clazz, GenerateValue<List<T>> generateValue) {
        return getOrPutList(tool, source, promptHash, 0, clazz, generateValue);
    }

    @Override
    public <T> CacheResult<T[]> getOrPutObjectArray(String tool, String source, String promptHash, long ttlSeconds, Class<T> clazz, Class<T[]> arrayClazz, GenerateValue<T[]> generateValue) {
        return new CacheResult<>(generateValue.generate(), null, false);
    }

    @Override
    public <T, U> CacheResult<T> getOrPutGeneric(String tool, String source, String promptHash, long ttlSeconds, Class<T> container, Class<U> contained, GenerateValue<T> generateValue) {
        return new CacheResult<>(generateValue.generate(), null, false);
    }

    @Override
    public <T, U> CacheResult<T> getOrPutGeneric(String tool, String source, String promptHash, Class<T> container, Class<U> contained, GenerateValue<T> generateValue) {
        return getOrPutGeneric(tool, source, promptHash, 0, container, contained, generateValue);
    }

    @Override
    public <T, U, V> CacheResult<T> getOrPutGeneric(String tool, String source, String promptHash, long ttlSeconds, Class<T> container, Class<U> contained, Class<V> contained2, GenerateValue<T> generateValue) {
        return new CacheResult<>(generateValue.generate(), null, false);
    }

    @Override
    public <T, U, V> CacheResult<T> getOrPutGeneric(String tool, String source, String promptHash, Class<T> container, Class<U> contained, Class<V> contained2, GenerateValue<T> generateValue) {
        return getOrPutGeneric(tool, source, promptHash, 0, container, contained, contained2, generateValue);
    }

    @Override
    public void putString(String tool, String source, String promptHash, long ttlSeconds, String response) {
    }

    @Override
    public void putString(String tool, String source, String promptHash, String response) {
    }

    @Override
    public void flush() {
    }

    @Override
    public <T> CacheResult<T[]> persistArrayResult(String tool, String source, String promptHash, long ttlSeconds, GenerateValue<T[]> generateValue) {
        return new CacheResult<>(generateValue.generate(), null, false);
    }
}
