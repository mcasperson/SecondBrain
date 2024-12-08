package secondbrain.domain.sanitize;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SanitizeListTest {

    @Test
    void testSanitize() {
        SanitizeList sanitizeList = new SanitizeList();
        String argument = "test, document, example";
        String document = "This is a test document. It contains several examples.";

        String expected = "test,document,example";
        String result = sanitizeList.sanitize(argument, document);

        assertEquals(expected, result);
    }

    @Test
    void testSanitizeWithNoMatches() {
        SanitizeList sanitizeList = new SanitizeList();
        String argument = "foo, bar, baz";
        String document = "This is a test document. It contains several examples.";

        String expected = "";
        String result = sanitizeList.sanitize(argument, document);

        assertEquals(expected, result);
    }

    @Test
    void testSanitizeWithEmptyArgument() {
        SanitizeList sanitizeList = new SanitizeList();
        String argument = "";
        String document = "This is a test document. It contains several examples.";

        String expected = "";
        String result = sanitizeList.sanitize(argument, document);

        assertEquals(expected, result);
    }

    @Test
    void testSanitizeWithEmptyDocument() {
        SanitizeList sanitizeList = new SanitizeList();
        String argument = "test, document, example";
        String document = "";

        String expected = "";
        String result = sanitizeList.sanitize(argument, document);

        assertEquals(expected, result);
    }
}
