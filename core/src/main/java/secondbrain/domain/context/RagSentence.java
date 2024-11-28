package secondbrain.domain.context;

/**
 * Represents a sentence and the ID of the document it came from. Unlike RagSentenceAndOriginal, the generated content
 * is no longer relevant. RagSentence is used to generate a list of the sources used for annotations/references.
 *
 * @param sentence The sentence from the RAG context
 * @param id       The ID of the RAG document
 */
public record RagSentence(String sentence, String id) {
}
