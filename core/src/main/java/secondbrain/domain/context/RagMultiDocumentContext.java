package secondbrain.domain.context;

import org.apache.commons.lang3.StringUtils;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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

    public List<String> getLinks() {
        return individualContexts.stream().map(RagDocumentContext::link).toList();
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
    public AnnotationResult<RagMultiDocumentContext<T>> annotateDocumentContext(final float minSimilarity,
                                                                                final int minWords,
                                                                                final SentenceSplitter sentenceSplitter,
                                                                                final SimilarityCalculator similarityCalculator,
                                                                                final SentenceVectorizer sentenceVectorizer) {

        final Set<RagSentenceAndOriginal> annotations = getAnnotations(minSimilarity, minWords, sentenceSplitter, similarityCalculator, sentenceVectorizer);
        final List<RagSentence> lookups = getAnnotationLookup(annotations);
        final String result = annotations
                .stream()
                // Use each of the annotations to update the document inline with the annotation index and then append the annotation
                .reduce(combinedDocument(),
                        (acc, entry) ->
                                // update the document with the annotation index
                                acc.replaceAll(Pattern.quote(entry.originalContext()), entry.originalContext() + " [" + (lookups.indexOf(entry.toRagSentence()) + 1) + "]"),
                        (acc1, acc2) -> acc1 + acc2)
                .trim()
                + System.lineSeparator()
                + System.lineSeparator()
                + lookupsToString(lookups);

        final int annotationIds = annotations.stream().map(RagSentenceAndOriginal::id).collect(Collectors.toSet()).size();

        return new AnnotationResult<>(result, (float) annotationIds / individualContexts.size(), this);
    }

    public String lookupsToString(final List<RagSentence> lookups) {

        final List<String> output = new ArrayList<>();
        for (int i = 0; i < lookups.size(); i++) {
            RagSentence lookup = lookups.get(i);
            output.add("* [" + (i + 1) + "]: " + lookup.sentence() + " (" + lookup.id() + ")");
        }
        return String.join(System.lineSeparator(), output);
    }

    public List<RagSentence> getAnnotationLookup(final Set<RagSentenceAndOriginal> annotations) {
        return annotations
                .stream()
                .map(RagSentenceAndOriginal::toRagSentence)
                .collect(Collectors.toSet())
                .stream().toList();
    }

    public Set<RagSentenceAndOriginal> getAnnotations(final float minSimilarity,
                                                      final int minWords,
                                                      final SentenceSplitter sentenceSplitter,
                                                      final SimilarityCalculator similarityCalculator,
                                                      final SentenceVectorizer sentenceVectorizer) {

        return sentenceSplitter.splitDocument(combinedDocument(), minWords)
                .stream()
                .filter(sentence -> !StringUtils.isBlank(sentence))
                // find the best match in each individual context, or no match at all
                .flatMap(sentence -> individualContexts.stream()
                        .map(rag -> rag.getClosestSentence(
                                sentence,
                                sentenceVectorizer.vectorize(sentence).vector(),
                                similarityCalculator,
                                minSimilarity))
                        .filter(Objects::nonNull)
                        .sorted(Comparator.comparingDouble(RagMatchedStringContext::match).reversed())
                        .limit(1))
                // Once we have the closest match, the match value is no longer relevant
                .map(RagMatchedStringContext::toRagSentenceAndOriginal)
                // Getting a set ensures that we don't have duplicates
                .collect(Collectors.toSet());
    }
}
