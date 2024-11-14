package secondbrain.domain.keyword;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class RakeKeywordExtractorTest {

    @Test
    public void testGetKeywords() {
        final RakeKeywordExtractor extractor = new RakeKeywordExtractor();
        final String text = "This is a test sentence for keyword extraction.";

        final List<String> keywords = extractor.getKeywords(text);

        assertNotNull(keywords);
        assertFalse(keywords.isEmpty());
    }
}