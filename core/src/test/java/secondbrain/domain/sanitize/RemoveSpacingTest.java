package secondbrain.domain.sanitize;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("NullAway")
class RemoveSpacingTest {

    private final RemoveSpacing removeSpacing = new RemoveSpacing();

    @Test
    void testSanitizeWithEmptyString() {
        String document = "";
        String result = removeSpacing.sanitize(document);
        assertEquals("", result);
    }

    @Test
    void testSanitizeWithNull() {
        String document = null;
        String result = removeSpacing.sanitize(document);
        assertNull(result);
    }

    @Test
    void testSanitizeWithNoSpacing() {
        String document = "This is a test document.";
        String result = removeSpacing.sanitize(document);
        assertEquals("This is a test document.", result);
    }

    @Test
    void testSanitizeWithSpacing() {
        String document = "This is a test document.\n\n\n\nThis is another test document.";
        String result = removeSpacing.sanitize(document);
        assertEquals("This is a test document.\n\nThis is another test document.", result);
    }

    @Test
    void testSanitizeWithMultipleSpacings() {
        String document = "Line 1\n\n\n\nLine 2\n\n\n\nLine 3";
        String result = removeSpacing.sanitize(document);
        assertEquals("Line 1\n\nLine 2\n\nLine 3", result);
    }
}