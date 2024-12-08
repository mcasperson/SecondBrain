package secondbrain.domain.sanitize;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SanitizeEmailTest {

    private final SanitizeEmail sanitizeEmail = new SanitizeEmail();

    @Test
    void testSanitizeWithEmptyString() {
        String document = "";
        String result = sanitizeEmail.sanitize(document, "");
        assertEquals("", result);
    }

    @Test
    void testSanitizeWithNull() {
        String document = null;
        String result = sanitizeEmail.sanitize(document, "");
        assertEquals("", result);
    }

    @Test
    void testSanitizeWithValidEmail() {
        String document = "Please contact us at support@example.com for assistance.";
        String result = sanitizeEmail.sanitize(document, document);
        assertEquals("support@example.com", result);
    }

    @Test
    void testSanitizeWithInvalidEmail() {
        String document = "Please contact us at admin@example.com for assistance.";
        String result = sanitizeEmail.sanitize(document, document);
        assertEquals("", result);
    }

    @Test
    void testSanitizeWithMultipleEmails() {
        String document = "Contact john.doe@example.com or jane.doe@example.org.";
        String result = sanitizeEmail.sanitize(document, document);
        assertEquals("john.doe@example.com", result);
    }

    @Test
    void testSanitizeWithInvalidEmails() {
        String document = "admin@example.com";
        String result = sanitizeEmail.sanitize(document, document);
        assertEquals("", result);
    }

    @Test
    void testSanitizeWithNoEmail() {
        String document = "There is no email address in this document.";
        String result = sanitizeEmail.sanitize(document, document);
        assertEquals("", result);
    }
}