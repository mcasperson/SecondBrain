package secondbrain.domain.async;

import java.util.Optional;

/**
 * Represents a service that can store and retrieve results asynchronously.
 */
public interface AsyncResults {
    /**
     * Adds a result to the store.
     *
     * @param key   The key to store the result under.
     * @param value The value to store.
     */
    void addResult(String key, String value);

    /**
     * Retrieves a result from the store.
     *
     * @param key The key to retrieve the result for.
     * @return The result, if it exists.
     */
    Optional<String> getResult(String key);
}
