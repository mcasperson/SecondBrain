package secondbrain.domain.vector;

/**
 * Vectorizes a sentence.
 */
public interface SentenceVectorizer {
    RagStringContext vectorize(String text);
}
