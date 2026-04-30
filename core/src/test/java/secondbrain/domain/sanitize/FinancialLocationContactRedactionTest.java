package secondbrain.domain.sanitize;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("NullAway")
class FinancialLocationContactRedactionTest {

    private FinancialLocationContactRedaction redaction;

    @BeforeEach
    void setUp() {
        redaction = new FinancialLocationContactRedaction();
        redaction.construct();
    }

    @Test
    void testNullReturnsEmpty() {
        assertEquals("", redaction.sanitize(null));
    }

    @Test
    void testBlankReturnsEmpty() {
        assertEquals("", redaction.sanitize("   "));
    }

    @Test
    void testEmptyReturnsEmpty() {
        assertEquals("", redaction.sanitize(""));
    }

    @Test
    void testEmailRedacted() {
        final String result = redaction.sanitize("Contact us at john.doe@example.com for help.");
        assertTrue(result.contains("{{{REDACTED-EMAIL}}}"), "Email should be redacted");
        assertTrue(!result.contains("john.doe@example.com"), "Original email should not appear");
    }

    @Test
    void testPhoneRedacted() {
        final String result = redaction.sanitize("Call us at 555-867-5309 for support.");
        assertTrue(result.contains("{{{REDACTED-PHONE}}}"), "Phone number should be redacted");
        assertTrue(!result.contains("555-867-5309"), "Original phone should not appear");
    }

    @Test
    void testCreditCardRedacted() {
        final String result = redaction.sanitize("My card number is 4111111111111111.");
        assertTrue(result.contains("{{{REDACTED-CREDIT-CARD}}}"), "Credit card number should be redacted");
        assertTrue(!result.contains("4111111111111111"), "Original credit card should not appear");
    }

    @Test
    void testSsnRedacted() {
        final String result = redaction.sanitize("My SSN is 123-45-6789.");
        assertTrue(result.contains("{{{REDACTED-SSN}}}"), "SSN should be redacted");
        assertTrue(!result.contains("123-45-6789"), "Original SSN should not appear");
    }

    @Test
    void testIpAddressRedacted() {
        final String result = redaction.sanitize("The server IP is 192.168.1.100.");
        assertTrue(result.contains("{{{REDACTED-IP}}}"), "IP address should be redacted");
        assertTrue(!result.contains("192.168.1.100"), "Original IP should not appear");
    }

    @Test
    void testUrlRedacted() {
        final String result = redaction.sanitize("Visit https://www.example.com for details.");
        assertTrue(result.contains("{{{REDACTED-URL}}}"), "URL should be redacted");
        assertTrue(!result.contains("https://www.example.com"), "Original URL should not appear");
    }

    @Test
    void testPlainTextUnchanged() {
        final String input = "The quarterly earnings report looks promising.";
        final String result = redaction.sanitize(input);
        assertEquals(input, result, "Plain text with no PII should be unchanged");
    }

    @Test
    void testMultiplePiiTypesRedacted() {
        final String result = redaction.sanitize(
                "Email john@example.com or call 555-123-4567, card 4111111111111111.");
        assertTrue(result.contains("{{{REDACTED-EMAIL}}}"), "Email should be redacted");
        assertTrue(result.contains("{{{REDACTED-PHONE}}}"), "Phone should be redacted");
        assertTrue(result.contains("{{{REDACTED-CREDIT-CARD}}}"), "Credit card should be redacted");
    }
}

