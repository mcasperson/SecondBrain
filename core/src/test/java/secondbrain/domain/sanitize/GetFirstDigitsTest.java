package secondbrain.domain.sanitize;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@SuppressWarnings("NullAway")
class GetFirstDigitsTest {

    private final GetFirstDigits getFirstDigits = new GetFirstDigits();

    @Test
    void testSanitizeWithNull() {
        String document = null;
        String result = getFirstDigits.sanitize(document);
        assertNull(result);
    }

    @Test
    void testSanitizeWithEmptyString() {
        String document = "";
        String result = getFirstDigits.sanitize(document);
        assertEquals("", result);
    }

    @Test
    void testSanitizeWithDigitsAtStart() {
        String document = "123abc";
        String result = getFirstDigits.sanitize(document);
        assertEquals("123", result);
    }

    @Test
    void testSanitizeWithSingleDigitAtStart() {
        String document = "5hello";
        String result = getFirstDigits.sanitize(document);
        assertEquals("5", result);
    }

    @Test
    void testSanitizeWithOnlyDigits() {
        String document = "999";
        String result = getFirstDigits.sanitize(document);
        assertEquals("999", result);
    }

    @Test
    void testSanitizeWithNoDigitsAtStart() {
        String document = "abc123";
        String result = getFirstDigits.sanitize(document);
        assertEquals("abc123", result);
    }

    @Test
    void testSanitizeWithWhitespaceBeforeDigits() {
        String document = " 123abc";
        String result = getFirstDigits.sanitize(document);
        assertEquals(" 123abc", result);
    }

    @Test
    void testSanitizeWithZeroAtStart() {
        String document = "0123";
        String result = getFirstDigits.sanitize(document);
        assertEquals("0123", result);
    }

    @Test
    void testSanitizeWithDigitsAndSpaces() {
        String document = "42 is the answer";
        String result = getFirstDigits.sanitize(document);
        assertEquals("42", result);
    }

    @Test
    void testSanitizeWithDigitsSpacesAndLineBreaks() {
        String document = "42\nis the answer";
        String result = getFirstDigits.sanitize(document);
        assertEquals("42", result);
    }

    @Test
    void testSanitizeWithNoDigits() {
        String document = "hello world";
        String result = getFirstDigits.sanitize(document);
        assertEquals("hello world", result);
    }

    @Test
    void testSanitizeWithSpecialCharactersAtStart() {
        String document = "!@#123";
        String result = getFirstDigits.sanitize(document);
        assertEquals("!@#123", result);
    }

    @Test
    void testSanitizeWithWhitespaceOnly() {
        String document = "   ";
        String result = getFirstDigits.sanitize(document);
        assertEquals("   ", result);
    }
}