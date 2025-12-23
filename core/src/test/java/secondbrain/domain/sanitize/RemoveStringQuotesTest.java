package secondbrain.domain.sanitize;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@SuppressWarnings("NullAway")
class RemoveStringQuotesTest {

    private final RemoveStringQuotes removeStringQuotes = new RemoveStringQuotes();

    @Test
    void testSanitizeWithNull() {
        String document = null;
        String result = removeStringQuotes.sanitize(document);
        assertNull(result);
    }

    @Test
    void testSanitizeWithEmptyString() {
        String document = "";
        String result = removeStringQuotes.sanitize(document);
        assertEquals("", result);
    }

    @Test
    void testSanitizeWithDoubleQuotes() {
        String document = "\"Hello World\"";
        String result = removeStringQuotes.sanitize(document);
        assertEquals("Hello World", result);
    }

    @Test
    void testSanitizeWithSingleQuotes() {
        String document = "'Hello World'";
        String result = removeStringQuotes.sanitize(document);
        assertEquals("Hello World", result);
    }

    @Test
    void testSanitizeWithNoQuotes() {
        String document = "Hello World";
        String result = removeStringQuotes.sanitize(document);
        assertEquals("Hello World", result);
    }

    @Test
    void testSanitizeWithOnlyOpeningDoubleQuote() {
        String document = "\"Hello World";
        String result = removeStringQuotes.sanitize(document);
        assertEquals("Hello World", result);
    }

    @Test
    void testSanitizeWithOnlyClosingDoubleQuote() {
        String document = "Hello World\"";
        String result = removeStringQuotes.sanitize(document);
        assertEquals("Hello World", result);
    }

    @Test
    void testSanitizeWithOnlyOpeningSingleQuote() {
        String document = "'Hello World";
        String result = removeStringQuotes.sanitize(document);
        assertEquals("Hello World", result);
    }

    @Test
    void testSanitizeWithOnlyClosingSingleQuote() {
        String document = "Hello World'";
        String result = removeStringQuotes.sanitize(document);
        assertEquals("Hello World", result);
    }

    @Test
    void testSanitizeWithQuotesInMiddle() {
        String document = "Hello \"quoted\" World";
        String result = removeStringQuotes.sanitize(document);
        assertEquals("Hello \"quoted\" World", result);
    }

    @Test
    void testSanitizeWithJustDoubleQuotes() {
        String document = "\"\"";
        String result = removeStringQuotes.sanitize(document);
        assertEquals("", result);
    }

    @Test
    void testSanitizeWithJustSingleQuotes() {
        String document = "''";
        String result = removeStringQuotes.sanitize(document);
        assertEquals("", result);
    }
}