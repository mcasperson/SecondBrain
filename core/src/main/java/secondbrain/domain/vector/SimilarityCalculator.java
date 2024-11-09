package secondbrain.domain.vector;

/**
 * Calculate the similarity between two vectors.
 */
public interface SimilarityCalculator {
    Double calculateSimilarity(Vector v1, Vector v2);
}
