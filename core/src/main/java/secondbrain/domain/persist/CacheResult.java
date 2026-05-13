package secondbrain.domain.persist;

import org.jspecify.annotations.Nullable;

/**
 * Represents the result of a cache operation.
 *
 * @param result    The value returned from the get operation
 * @param exception The exception raised while generating the value
 * @param fromCache True if the value was retrieved from cache, false if it was computed
 * @param <T>       The result type
 */
public record CacheResult<T>(@Nullable T result, @Nullable Throwable exception, boolean fromCache) {
}
