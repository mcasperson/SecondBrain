package secondbrain.domain.vector;

import org.apache.commons.lang3.tuple.Pair;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.PriorityQueue;

import static java.util.Comparator.comparing;

/**
 * Represents a document made up of many individual context strings.
 * @param document The document
 * @param sentences The individual context strings that make up the documents
 */
public record RagDocumentContext(String document, List<RagStringContext> sentences) {
    /**
     * Given a vector, find the closest sentence in the document.
     * @param vector The vector to compare against
     * @param similarityCalculator The similarity calculator to use
     * @param minSimilarity The minimum similarity to consider
     * @return The closest sentence, or null if none are close enough
     */
    @Nullable public RagStringContext getClosestSentence(
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

        return bestSimilarity.getLeft();
    }
}
