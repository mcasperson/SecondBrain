package secondbrain.domain.vector;

public interface SentenceVectorizer {
    RagStringContext vectorize(String text);
}
