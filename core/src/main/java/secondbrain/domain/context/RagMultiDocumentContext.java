package secondbrain.domain.context;

import org.apache.commons.lang3.StringUtils;

import java.util.*;

/**
 * RagDocumentContext captures the details of a single document to be passed to the LLM. RagMultiDocumentContext
 * captures the combined context of multiple documents, while retaining links to the individual contexts.
 *
 * @param combinedDocument   The combined context to be sent to the LLM
 * @param individualContexts The individual documents that all contribute to the combined context
 */
public record RagMultiDocumentContext<T>(String combinedDocument, List<RagDocumentContext<T>> individualContexts) {
    public List<String> getIds() {
        return individualContexts.stream().map(RagDocumentContext::id).toList();
    }

    public List<T> getMetas() {
        return individualContexts.stream().map(RagDocumentContext::meta).toList();
    }

    /**
     * The document held by this object often needs to undergo some transformation, from raw text, to being sanitized,
     * to being marked up, as part of a LLM template. The individual contexts neve change though.
     *
     * @param document The new document
     * @return A new copy of this object with the new document
     */
    public RagMultiDocumentContext<T> updateDocument(final String document) {
        return new RagMultiDocumentContext<T>(document, individualContexts);
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
        return getAnnotations(minSimilarity, minWords, sentenceSplitter, similarityCalculator, sentenceVectorizer)
                .entrySet()
                .stream()
                // Use each of the annotations to update the document inline with the annotation index and then append the annotation
                .reduce(combinedDocument() + System.lineSeparator(),
                        (acc, entry) ->
                                // update the document with the annotation index
                                acc.replaceAll(entry.getKey().originalContext(), entry.getKey().originalContext() + " [" + entry.getValue() + "]")
                                        // append the annotation
                                        + System.lineSeparator()
                                        + "* [" + entry.getValue() + "]: " + entry.getKey().context() + " (" + entry.getKey().id() + ")",
                        (acc1, acc2) -> acc1 + acc2)
                .trim();
    }

    public Map<RagMatchedStringContext, Integer> getAnnotations(final float minSimilarity,
                                                                final int minWords,
                                                                final SentenceSplitter sentenceSplitter,
                                                                final SimilarityCalculator similarityCalculator,
                                                                final SentenceVectorizer sentenceVectorizer) {
        int index = 1;

        final Map<RagMatchedStringContext, Integer> annotationMap = new HashMap<>();

        for (var sentence : sentenceSplitter.splitDocument(combinedDocument(), minWords)) {

            if (StringUtils.isBlank(sentence)) {
                continue;
            }

            /*
                Find the closest matching sentence from the source context over the
                minimum similarity threshold. Ignore any failures.
             */
            final List<RagMatchedStringContext> closestMatch = individualContexts().stream()
                    .map(rag -> rag.getClosestSentence(
                            sentence,
                            sentenceVectorizer.vectorize(sentence).vector(),
                            similarityCalculator,
                            minSimilarity))
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparingDouble(RagMatchedStringContext::match))
                    .toList();

            if (!closestMatch.isEmpty()) {

                final boolean fromCache = annotationMap.containsKey(closestMatch.getLast());
                if (!fromCache) {
                    annotationMap.put(closestMatch.getLast(), index);
                    ++index;
                }
            }
        }

        return annotationMap;
    }
}
