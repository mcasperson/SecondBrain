package secondbrain.domain.context;

/**
 * Represents a single string and it's associated vector.
 *
 * @param context The context string
 * @param match   How closely the context matched the input
 */
public record RagMatchedStringContext(String context, double match, String id) {
}
