package secondbrain.domain.context;

/**
 * Vectorizes a sentence.
 */
public interface SentenceVectorizer {
    RagStringContext vectorize(String text);
}
