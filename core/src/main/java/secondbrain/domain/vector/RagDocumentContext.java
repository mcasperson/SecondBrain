package secondbrain.domain.vector;

import io.vavr.control.Try;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.PriorityQueue;

import static java.util.Comparator.comparing;

/**
 * Represents a document made up of many individual context strings.
 * <p>
 * This record is used to capture the information used to answer a prompt through many different stages:
 * - The original document
 * - The original document formatted as a prompt to be passed to an LLM
 * - The answer from an LLM
 * <p>
 * As the document is processed, it retains the sentences that made up the original document (i.e. the document
 * attribute changes, by the sentences do not). This is how we eventually link source material to the generated answer.
 *
 * @param document  The document
 * @param sentences The individual context strings that make up the documents
 */
public record RagDocumentContext(String document, List<RagStringContext> sentences) {
    /**
     * Given a vector, find the closest sentence in the list of sentences.
     *
     * @param vector               The vector to compare against
     * @param similarityCalculator The similarity calculator to use
     * @param minSimilarity        The minimum similarity to consider
     * @return The closest sentence, or null if none are close enough
     */
    @Nullable
    private RagStringContext getClosestSentence(
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

    /**
     * Annotate the document with the closest matching sentence from the source sentences..
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
            var closestMatch = Try.of(() -> getClosestSentence(
                            sentenceVectorizer.vectorize(sentence).vector(),
                            similarityCalculator,
                            minSimilarity))
                    .onFailure(throwable -> System.err.println("Failed to get closest sentence: " + ExceptionUtils.getRootCauseMessage(throwable)))
                    .getOrElse(() -> null);

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
