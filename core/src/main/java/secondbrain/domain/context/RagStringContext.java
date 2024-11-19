package secondbrain.domain.context;

/**
 * Represents a single string and it's associated vector.
 *
 * @param context The context string
 * @param vector  The context vector
 */
public record RagStringContext(String context, Vector vector) {
}
