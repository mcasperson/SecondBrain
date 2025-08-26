package secondbrain.domain.persist;

/**
 * Represents the result of a cache operation.
 *
 * @param result    The value returned from the get operation
 * @param fromCache True if the value was retrieved from cache, false if it was computed
 * @param <T>
 */
public record CacheResult<T>(T result, boolean fromCache) {
}
