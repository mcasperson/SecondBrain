package secondbrain.domain.sanitize;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("NullAway")
class RemoveMarkdnUrlsTest {

    private final RemoveMarkdnUrls removeMarkdnUrls = new RemoveMarkdnUrls();

    @Test
    void testSanitizeWithEmptyString() {
        String document = "";
        String result = removeMarkdnUrls.sanitize(document);
        assertEquals("", result);
    }

    @Test
    void testSanitizeWithNull() {
        String document = null;
        String result = removeMarkdnUrls.sanitize(document);
        assertNull(result);
    }

    @Test
    void testSanitizeWithNoSpecialCharacters() {
        String document = "This is a test document.";
        String result = removeMarkdnUrls.sanitize(document);
        assertEquals("This is a test document.", result);
    }

    @Test
    void testSanitizeWithSpecialCharacters() {
        String document = "This is a test document.|>><<";
        String result = removeMarkdnUrls.sanitize(document);
        assertEquals("This is a test document.     ", result);
    }

    @Test
    void testSanitizeWithMultipleSpecialCharacters() {
        String document = "Line 1|>><<Line 2|>><<Line 3";
        String result = removeMarkdnUrls.sanitize(document);
        assertEquals("Line 1     Line 2     Line 3", result);
    }

    @Test
    void testSanitizeWithMarkdnUrls() {
        String document = "<url|https://www.example.com>";
        String result = removeMarkdnUrls.sanitize(document);
        assertEquals(" url https://www.example.com ", result);
    }
}