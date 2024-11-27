package secondbrain.domain.context;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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

        RagDocumentContext<String> context1 = new RagDocumentContext<>("This is a test document.", List.of(doc1), "doc1");
        RagDocumentContext<String> context2 = new RagDocumentContext<>("It contains multiple sentences.", List.of(doc2), "doc2");

        // Create a multi-document context
        RagMultiDocumentContext<String> multiContext = new RagMultiDocumentContext<>(
                "This is a test document after processing. It contains multiple sentences after processing.",
                List.of(context1, context2)
        );

        // Get annotations
        Map<RagMatchedStringContext, Integer> annotations = multiContext.getAnnotations(
                0.8f,  // minSimilarity
                3,     // minWords
                sentenceSplitter,
                similarityCalculator,
                sentenceVectorizer
        );

        // Verify the annotations
        assertEquals(2, annotations.size());
        assertTrue(annotations.entrySet().stream().anyMatch(e -> e.getKey().context().equals("This is a test document")));
        assertTrue(annotations.entrySet().stream().anyMatch(e -> e.getKey().context().equals("It contains multiple sentences")));

        // Annotate the document
        String annotatedDocument = multiContext.annotateDocumentContext(
                0.8f,  // minSimilarity
                3,     // minWords
                sentenceSplitter,
                similarityCalculator,
                sentenceVectorizer
        );
        assertTrue(annotatedDocument.contains("[1]: This is a test document"));
        assertTrue(annotatedDocument.contains("[2]: It contains multiple sentences"));
    }

    // Mock classes for dependencies
    static class MockSentenceSplitter implements SentenceSplitter {
        @Override
        public List<String> splitDocument(String document, int minWords) {
            return List.of("This is a test document after processing", "It contains multiple sentences after processing");
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
        public RagStringContext vectorize(String sentence) {
            if (sentence.equals("This is a test document after processing")) {
                return new RagStringContext(sentence, new Vector(1d));  // Mock vector
            }

            if (sentence.equals("It contains multiple sentences after processing")) {
                return new RagStringContext(sentence, new Vector(2d));  // Mock vector
            }

            return new RagStringContext(sentence, new Vector());  // Mock vector
        }
    }
}