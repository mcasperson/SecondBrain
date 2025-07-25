package secondbrain.domain.context;

import org.apache.commons.lang3.tuple.Pair;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.limit.TrimResult;
import secondbrain.domain.tooldefs.IntermediateResult;
import secondbrain.domain.tooldefs.MetaObjectResults;

import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;

import static java.util.Comparator.comparing;

/**
 * Represents a single string document made up of many individual context sentences.
 * <p>
 * When the context passed to an LLM is just a single document, the RagDocumentContext is
 * all that is needed to capture the context and the individual sentences that make up
 * context.
 * <p>
 * When multiple documents make up the context passed to an LLM, the RagMultiDocumentContext
 * class is used to capture many RagDocumentContext instances.
 * <p>
 * RagDocumentContext differs from IndividualContext in RagDocumentContext captures the content of
 * an external data source as a string and then breaks down the string into individual sentences.
 * <p>
 * RagDocumentContext also captures the metadata and intermediate results associated with the document.
 * This data is usually saved to files to be consumed by an external script. For example, you may want
 * to build a PDF document from the results and capture a table of facts from the source emails
 * such as the sender, recipient, subject, and date. This can be extracted from the metadata rather than
 * attempting to be built into the LLM response.
 *
 * @param contextLabel   The label of the context when it is presented to the LLM. This provides a way to reference the context from the prompt.
 * @param document       The string representation of the external data. This may be the raw text of a document, a sanitized version, or even a summarized version.
 * @param sentences      The individual context strings that make up the documents. These are always generated from the raw document text.
 * @param id             The ID of the document. The ID format if dependent on the source of the document.
 * @param source         The original object that was used to generate the document. This will typically be a POJO retrieved from the database or an external API.
 * @param metadata       The metadata associated with the document, such as the source, author, etc. Metadata can also be generated, such as categorizing the text using a second call to the LLM.
 * @param link           The link to the document. These links are often appended to the end of the result to provide a way to access the original document.
 * @param keywordMatches The keywords that were matched in the document. These are often used in the annotations to highlight why the document was selected as context for the LLM.
 * @param group          The group that the document belongs to
 */
public record RagDocumentContext<T>(String contextLabel,
                                    String document,
                                    List<RagStringContext> sentences,
                                    String id,
                                    @Nullable T source,
                                    @Nullable MetaObjectResults metadata,
                                    @Nullable IntermediateResult intermediateResult,
                                    @Nullable String link,
                                    @Nullable List<String> keywordMatches,
                                    @Nullable String group) {

    public RagDocumentContext(final String contextLabel, final String document, final List<RagStringContext> sentences, final String id, final @Nullable T source, final @Nullable String link, final @Nullable List<String> keywordMatches, final @Nullable String group) {
        this(contextLabel, document, sentences, id, source, null, null, link, keywordMatches, group);
    }

    public RagDocumentContext(final String contextLabel, final String document, final List<RagStringContext> sentences, final String id, final @Nullable T source, final @Nullable String link, final @Nullable List<String> keywordMatches) {
        this(contextLabel, document, sentences, id, source, null, null, link, keywordMatches, null);
    }

    public RagDocumentContext(final String contextLabel, final String document, final List<RagStringContext> sentences, final String id) {
        this(contextLabel, document, sentences, id, null, null, null, null, null, null);
    }

    public RagDocumentContext(final String contextLabel, final String document, final List<RagStringContext> sentences, final String id, final @Nullable T source, final @Nullable String link) {
        this(contextLabel, document, sentences, id, source, null, null, link, null, null);
    }

    public RagDocumentContext(final String contextLabel, final String document, final List<RagStringContext> sentences) {
        this(contextLabel, document, sentences, "", null, null, null, null, null, null);
    }

    public String getGroup() {
        return Objects.requireNonNullElse(group, "");
    }

    public String getLink() {
        return Objects.requireNonNullElse(link, "");
    }

    /**
     * The document held by this object often needs to undergo some transformation, from raw text, to being sanitized,
     * to being marked up, as part of a LLM template. In some cases, it may even be appropriate to summarize the document
     * via an LLM while retaining the original sentences and their vectors. The sentences never change though to ensure
     * the original context is available.
     *
     * @param document The new document
     * @return A new copy of this object with the new document
     */
    public RagDocumentContext<T> updateDocument(final String document) {
        return new RagDocumentContext<>(contextLabel, document, sentences, id, source, metadata, intermediateResult, link, keywordMatches, group);
    }

    public RagDocumentContext<T> updateDocument(final TrimResult trimResult) {
        return new RagDocumentContext<>(contextLabel, trimResult.document(), sentences, id, source, metadata, intermediateResult, link, trimResult.keywordMatches(), group);
    }

    public RagDocumentContext<T> updateGroup(final String group) {
        return new RagDocumentContext<>(contextLabel, document, sentences, id, source, metadata, intermediateResult, link, keywordMatches, group);
    }

    public RagDocumentContext<T> updateMetadata(final MetaObjectResults metadata) {
        return new RagDocumentContext<>(contextLabel, document, sentences, id, source, metadata, intermediateResult, link, keywordMatches, group);
    }

    public RagDocumentContext<T> updateContextLabel(final String contextLabel) {
        return new RagDocumentContext<>(contextLabel, document, sentences, id, source, metadata, intermediateResult, link, keywordMatches, group);
    }

    public RagDocumentContext<T> updateLink(final String link) {
        return new RagDocumentContext<>(contextLabel, document, sentences, id, source, metadata, intermediateResult, link, keywordMatches, group);
    }

    public RagDocumentContext<T> updateIntermediateResult(@Nullable final IntermediateResult intermediateResult) {
        return new RagDocumentContext<>(contextLabel, document, sentences, id, source, metadata, intermediateResult, link, keywordMatches, group);
    }

    public MetaObjectResults getMetadata() {
        if (metadata == null) {
            return new MetaObjectResults();
        }

        return metadata;
    }

    /**
     * Convert this into a RagDocumentContext with a Void source type. This is mostly used when
     * collating a lot of context generated by child tools and you need to have a common
     * type to work with.
     */
    public RagDocumentContext<Void> getRagDocumentContextVoid() {
        return new RagDocumentContext<>(
                contextLabel,
                document,
                sentences,
                id,
                null,
                metadata,
                intermediateResult,
                link,
                keywordMatches,
                group);
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

        return new RagMatchedStringContext(bestSimilarity.getLeft().context(), context, bestSimilarity.getRight(), id);
    }
}
