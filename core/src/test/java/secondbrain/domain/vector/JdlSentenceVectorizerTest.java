package secondbrain.domain.vector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class JdlSentenceVectorizerTest {
    @Test
    public void testVectorize() {
        final JdlSentenceVectorizer jdlSentenceVectorizer = new JdlSentenceVectorizer();
        final String text = "This is a test sentence.";
        final RagStringContext vector = jdlSentenceVectorizer.vectorize(text);
        assertNotNull(vector.vector());
    }
}
