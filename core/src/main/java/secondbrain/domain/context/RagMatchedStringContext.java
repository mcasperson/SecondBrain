package secondbrain.domain.context;

/**
 * Represents a string matched to rag context and the associated id. This is used to find content in the
 * RAG context that has a close match to the generated output as a way of linking output back to the original
 * context.
 *
 * @param originalContext The original context string
 * @param context         The generated output
 * @param match           How closely the context matched the input
 * @param id              The id of the original context
 */
public record RagMatchedStringContext(String originalContext, String context, double match, String id) {
    public RagSentenceAndOriginal toRagSentenceAndOriginal() {
        return new RagSentenceAndOriginal(originalContext, context, id);
    }
}
