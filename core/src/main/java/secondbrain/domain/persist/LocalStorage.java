package secondbrain.domain.persist;

/**
 * Provides a way to cache results of expensive operations.
 */
public interface LocalStorage {
    /**
     * Get the value associated with a tool, source, and prompt hash.
     *
     * @param tool       The name of the tool
     * @param source     A way to identify the source
     * @param promptHash A way to identify the prompt
     * @return The value, if one was saved
     */
    String getString(String tool, String source, String promptHash);

    /**
     * Get the value associated with a tool, source, and prompt hash, or save a new value if one is not found.
     *
     * @param tool          The name of the tool
     * @param source        A way to identify the source
     * @param promptHash    A way to identify the prompt
     * @param generateValue A way to generate a new value
     * @return The value, if one was saved, or the generated value
     */
    String getOrPutString(String tool, String source, String promptHash, GenerateValue generateValue);

    /**
     * Save a value associated with a tool, source, and prompt hash.
     *
     * @param tool       The name of the tool
     * @param source     A way to identify the source
     * @param promptHash A way to identify the prompt
     * @param value      The value to save
     */
    void putString(String tool, String source, String promptHash, String value);
}
