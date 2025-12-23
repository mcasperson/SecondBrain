package secondbrain.domain.tools.zendesk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SuppressWarnings("NullAway")
class SanitizeOrganizationTest {

    private final SanitizeOrganization sanitizeOrganization = new SanitizeOrganization();

    @Test
    void testSanitizeWithEmptyString() {
        String document = "";
        String result = sanitizeOrganization.sanitize(document, document);
        assertEquals("", result);
    }

    @Test
    void testSanitizeWithNull() {
        String document = null;
        String result = sanitizeOrganization.sanitize(document, document);
        assertEquals("", result);
    }

    @Test
    void testSanitizeWithValidOrganization() {
        String document = "Valid Organization";
        String result = sanitizeOrganization.sanitize(document, document);
        assertEquals("Valid Organization", result);
    }

    @Test
    void testSanitizeWithInvalidOrganization() {
        String document = "zendesk";
        String result = sanitizeOrganization.sanitize(document, document);
        assertEquals("", result);
    }

    @Test
    void testSanitizeWithMultipleInvalidOrganizations() {
        String document = "helpdesk";
        String result = sanitizeOrganization.sanitize(document, document);
        assertEquals("", result);
    }

    @Test
    void testSanitizeWithNoInvalidOrganization() {
        String document = "Some random text";
        String result = sanitizeOrganization.sanitize(document, document);
        assertEquals("Some random text", result);
    }
}