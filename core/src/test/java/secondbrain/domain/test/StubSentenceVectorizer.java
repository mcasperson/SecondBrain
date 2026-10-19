package secondbrain.domain.test;

import secondbrain.domain.context.RagStringContext;
import secondbrain.domain.context.SentenceVectorizer;
import secondbrain.domain.exceptions.InternalFailure;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A sentence vectorizer that returns no vectors, so tests do not need to load an embedding model. The hidden text
 * passed with each call is recorded, and the vectorizer can be made to fail.
 */
public final class StubSentenceVectorizer implements SentenceVectorizer {
    public final List<String> hiddenText = new CopyOnWriteArrayList<>();
    private volatile boolean fail;

    /**
     * Makes every subsequent call throw an InternalFailure, as happens when the embedding model is unavailable.
     */
    public StubSentenceVectorizer failAll() {
        fail = true;
        return this;
    }

    @Override
    public RagStringContext vectorize(final String text, final String hiddenText) {
        throw new UnsupportedOperationException("Single sentence vectorization is not supported");
    }

    @Override
    public List<RagStringContext> vectorize(final List<String> text, final String hiddenText) {
        this.hiddenText.add(hiddenText);
        return vectorize(text);
    }

    @Override
    public RagStringContext vectorize(final String text) {
        throw new UnsupportedOperationException("Single sentence vectorization is not supported");
    }

    @Override
    public List<RagStringContext> vectorize(final List<String> text) {
        if (fail) {
            throw new InternalFailure("Vectorizer unavailable");
        }
        return List.of();
    }
}
