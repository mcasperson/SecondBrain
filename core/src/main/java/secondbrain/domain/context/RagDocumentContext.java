package secondbrain.domain.context;

import org.apache.commons.lang3.StringUtils;
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
 */
public record RagDocumentContext(String document, List<RagStringContext> sentences, String id) {

    public RagDocumentContext(String document, List<RagStringContext> sentences) {
        this(document, sentences, "");
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

        return new RagMatchedStringContext(bestSimilarity.getLeft().context(), bestSimilarity.getRight());
    }

    /**
     * Annotate the document with the closest matching sentence from the source sentences.
     * This overcomes one of the problems with LLMs where you are not quite sure where it
     * got its answer from. By annotating the document with the source sentence, you can
     * quickly determine where the LLMs answer came from.
     *
     * @return The annotated result
     */
    public String annotateDocumentContext(final float minSimilarity,
                                          final int minWords,
                                          final SentenceSplitter sentenceSplitter,
                                          final SimilarityCalculator similarityCalculator,
                                          final SentenceVectorizer sentenceVectorizer) {
        String retValue = document();
        int index = 1;
        for (var sentence : sentenceSplitter.splitDocument(document())) {

            if (StringUtils.isBlank(sentence)) {
                continue;
            }

            /*
                Skip sentences that are too short to be useful. This avoids trying to match
                short items in bullet point lists.
             */
            if (sentence.split("\\s+").length < minWords) {
                continue;
            }

            /*
                Find the closest matching sentence from the source context over the
                minimum similarity threshold. Ignore any failures.
             */
            var closestMatch = getClosestSentence(
                    sentenceVectorizer.vectorize(sentence).vector(),
                    similarityCalculator,
                    minSimilarity);

            if (closestMatch != null) {
                // Annotate the original document
                retValue = retValue.replace(sentence, sentence + " [" + index + "]");

                // The start of the list of annotations has an extra line break
                if (index == 1) {
                    retValue += System.lineSeparator();
                }

                // Make a note of the source sentence
                retValue += System.lineSeparator()
                        + "* [" + index + "]: " + closestMatch.context();

                ++index;
            }
        }

        return retValue;
    }
}
