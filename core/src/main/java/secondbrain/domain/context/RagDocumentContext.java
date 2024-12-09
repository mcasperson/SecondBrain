package secondbrain.domain.context;

import org.apache.commons.lang3.tuple.Pair;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.PriorityQueue;

import static java.util.Comparator.comparing;

/**
 * Represents a single document made up of many individual context sentences.
 * <p>
 * When the context passed to an LLM is just a single document, the RagDocumentContext is
 * all that is needed to capture the context and the individual sentences that make up
 * context.
 * <p>
 * When multiple documents make up the context passed to an LLM, the RagMultiDocumentContext
 * class is used to capture many RagDocumentContext instances.
 *
 * @param document  The document
 * @param sentences The individual context strings that make up the documents
 * @param id        The ID of the document
 * @param meta      The metadata associated with the document
 * @param link      The link to the document
 */
public record RagDocumentContext<T>(String document, List<RagStringContext> sentences, String id, T meta, String link) {

    public RagDocumentContext(final String document, final List<RagStringContext> sentences, String id) {
        this(document, sentences, id, null, null);
    }

    public RagDocumentContext(final String document, final List<RagStringContext> sentences) {
        this(document, sentences, "", null, null);
    }

    /**
     * The document held by this object often needs to undergo some transformation, from raw text, to being sanitized,
     * to being marked up, as part of a LLM template. In some cases it may even be appropriate to summarize the document
     * via an LLM while retaining the original sentences and their vectors. The sentences never change though to ensure
     * the original context is available.
     *
     * @param document The new document
     * @return A new copy of this object with the new document
     */
    public RagDocumentContext<T> updateDocument(final String document) {
        return new RagDocumentContext<>(document, sentences, id, meta, link);
    }

    public RagDocumentContext<T> updateLink(final String link) {
        return new RagDocumentContext<>(document, sentences, id, meta, link);
    }

    /**
     * Given a vector, find the closest sentence in the list of sentences.
     *
     * @param vector               The vector to compare against
     * @param similarityCalculator The similarity calculator to use
     * @param minSimilarity        The minimum similarity to consider
     * @return The closest sentence, or null if none are close enough
     */
    @Nullable
    public RagMatchedStringContext getClosestSentence(
            final String context,
            final Vector vector,
            final SimilarityCalculator similarityCalculator,
            final double minSimilarity) {
        if (sentences.isEmpty()) {
            return null;
        }

        var vectorToSimilarityPq = new PriorityQueue<Pair<RagStringContext, Double>>(comparing(Pair::getRight));

        for (var candidate : sentences) {
            var similarity = similarityCalculator.calculateSimilarity(vector, candidate.vector());
            vectorToSimilarityPq.offer(Pair.of(candidate, similarity));

            if (vectorToSimilarityPq.size() > 1) {
                vectorToSimilarityPq.poll();
            }
        }

        if (vectorToSimilarityPq.isEmpty()) {
            return null;
        }

        var bestSimilarity = vectorToSimilarityPq.peek();

        if (bestSimilarity.getRight() < minSimilarity) {
            return null;
        }

        return new RagMatchedStringContext(context, bestSimilarity.getLeft().context(), bestSimilarity.getRight(), id);
    }
}
