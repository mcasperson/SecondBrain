package secondbrain.domain.context;

/**
 * This record matches a sentence in the generated output to the RAG context. Unlike RagMatchedStringContext,
 * how closely the context matched the input is no longer relevant.
 *
 * @param originalContext The sentence in the RAG context
 * @param context         The sentence in the generated output
 * @param id              The ID of the RAG document
 */
public record RagSentenceAndOriginal(String originalContext, String context, String id) {
    public RagSentence toRagSentence() {
        return new RagSentence(context, id);
    }
}
