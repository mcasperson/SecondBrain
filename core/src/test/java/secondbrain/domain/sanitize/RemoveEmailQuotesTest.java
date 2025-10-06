package secondbrain.domain.sanitize;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RemoveEmailQuotesTest {

    private final RemoveEmailQuotes removeEmailQuotes = new RemoveEmailQuotes();

    @Test
    void testSanitizeWithEmptyString() {
        String document = "";
        String result = removeEmailQuotes.sanitize(document);
        assertEquals("", result);
    }

    @Test
    void testSanitizeWithNull() {
        String document = null;
        String result = removeEmailQuotes.sanitize(document);
        assertNull(result);
    }

    @Test
    void testSanitizeWithNoQuotes() {
        String document = "This is a regular email message.";
        String result = removeEmailQuotes.sanitize(document);
        assertEquals("This is a regular email message.", result);
    }

    @Test
    void testSanitizeWithSingleQuotedLine() {
        String document = "> This is quoted text";
        String result = removeEmailQuotes.sanitize(document);
        assertEquals("", result);
    }

    @Test
    void testSanitizeWithMultipleQuotedLines() {
        String document = "> Line 1\n> Line 2\n> Line 3";
        String result = removeEmailQuotes.sanitize(document);
        assertEquals("", result);
    }

    @Test
    void testSanitizeWithMixedContent() {
        String document = "Original message\n> Quoted reply\nMore original text";
        String result = removeEmailQuotes.sanitize(document);
        assertEquals("Original message\n\nMore original text", result);
    }

    @Test
    void testSanitizeWithMultipleLevelQuotes() {
        String document = "New message\n> First level quote\n>> Second level quote\nOriginal text";
        String result = removeEmailQuotes.sanitize(document);
        assertEquals("New message\n\n\nOriginal text", result);
    }

    @Test
    void testSanitizeWithQuotesAtEnd() {
        String document = "My response here\n> Original message\n> More quoted text";
        String result = removeEmailQuotes.sanitize(document);
        assertEquals("My response here", result);
    }

    @Test
    void testSanitizeWithWhitespaceBeforeQuote() {
        String document = "Text\n  > Quoted with spaces";
        String result = removeEmailQuotes.sanitize(document);
        assertEquals("Text\n  > Quoted with spaces", result);
    }

    @Test
    void testSanitizeWithGreaterThanInText() {
        String document = "5 > 3 is true\nNormal text";
        String result = removeEmailQuotes.sanitize(document);
        assertEquals("5 > 3 is true\nNormal text", result);
    }
}
