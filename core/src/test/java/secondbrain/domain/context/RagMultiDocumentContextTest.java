package secondbrain.domain.context;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagMultiDocumentContextTest {

    @Test
    void testGetAnnotations() {
        // Create mock objects for dependencies
        SentenceSplitter sentenceSplitter = new MockSentenceSplitter();
        SimilarityCalculator similarityCalculator = new MockSimilarityCalculator();
        SentenceVectorizer sentenceVectorizer = new MockSentenceVectorizer();

        // Create individual contexts
        final RagStringContext doc1 = new RagStringContext("This is a test document", new Vector(1d));
        final RagStringContext doc2 = new RagStringContext("It contains multiple sentences", new Vector(2d));

        RagDocumentContext<String> context1 = new RagDocumentContext<>("test", "document", "This is a test document.", List.of(doc1), "doc1");
        RagDocumentContext<String> context2 = new RagDocumentContext<>("test", "document", "It contains multiple sentences.", List.of(doc2), "doc2");

        // Create a multi-document context
        RagMultiDocumentContext<String> multiContext = new RagMultiDocumentContext<>(
                "This is a test document after processing. This is a test document after processing. It contains multiple sentences after processing.",
                List.of(context1, context2)
        );

        // Get annotations
        Set<RagSentenceAndOriginal> annotations = multiContext.getAnnotations(
                0.8f,  // minSimilarity
                3,     // minWords
                sentenceSplitter,
                similarityCalculator,
                sentenceVectorizer
        );

        // Verify the annotations
        assertEquals(2, annotations.size());
        assertTrue(annotations.stream().anyMatch(e -> e.originalContext().equals("This is a test document")));
        assertTrue(annotations.stream().anyMatch(e -> e.originalContext().equals("It contains multiple sentences")));

        // Get lookups
        final List<RagSentence> lookups = multiContext.getAnnotationLookup(annotations);
        assertEquals(2, lookups.size());


        // Annotate the document
        AnnotationResult<RagMultiDocumentContext<String>> annotatedDocument = multiContext.annotateDocumentContext(
                0.8f,  // minSimilarity
                3,     // minWords
                sentenceSplitter,
                similarityCalculator,
                sentenceVectorizer
        );
        assertTrue(annotatedDocument.annotations().contains("[1]: This is a test document"));
        assertTrue(annotatedDocument.annotations().contains("[2]: It contains multiple sentences"));
        assertEquals(1, annotatedDocument.annotationCoverage());
    }

    // Mock classes for dependencies
    static class MockSentenceSplitter implements SentenceSplitter {
        @Override
        public List<String> splitDocument(String document, int minWords) {
            return List.of("This is a test document after processing", "This is a test document after processing", "It contains multiple sentences after processing");
        }
    }

    static class MockSimilarityCalculator implements SimilarityCalculator {
        @Override
        public Double calculateSimilarity(Vector vector1, Vector vector2) {
            if (vector1.dimension() == 0) {
                return 0d;
            }

            if (vector1.get(0) == vector2.get(0)) {
                return 1d;
            }

            return 0d;
        }
    }

    static class MockSentenceVectorizer implements SentenceVectorizer {
        @Override
        public RagStringContext vectorize(final String text, final String hiddenText) {
            return vectorize(text);
        }

        @Override
        public List<RagStringContext> vectorize(final List<String> text, final String hiddenText) {
            if (text == null || text.isEmpty()) {
                return List.of();
            }

            return text.stream()
                    .map(t -> vectorize(t, hiddenText))
                    .toList();
        }

        @Override
        public RagStringContext vectorize(final String sentence) {
            if (sentence.equals("This is a test document after processing")) {
                return new RagStringContext(sentence, new Vector(1d));  // Mock vector
            }

            if (sentence.equals("It contains multiple sentences after processing")) {
                return new RagStringContext(sentence, new Vector(2d));  // Mock vector
            }

            return new RagStringContext(sentence, new Vector());  // Mock vector
        }

        @Override
        public List<RagStringContext> vectorize(final List<String> text) {
            if (text == null || text.isEmpty()) {
                return List.of();
            }

            return text.stream()
                    .map(this::vectorize)
                    .toList();
        }
    }
}