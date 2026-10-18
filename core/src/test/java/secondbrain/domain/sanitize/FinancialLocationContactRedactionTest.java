package secondbrain.domain.sanitize;

import ai.philterd.phileas.PhileasConfiguration;
import ai.philterd.phileas.services.context.DefaultContextService;
import ai.philterd.phileas.services.filters.filtering.PlainTextFilterService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import secondbrain.domain.json.JsonDeserializerJackson;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("NullAway")
class FinancialLocationContactRedactionTest {

    private final List<LogRecord> logRecords = new CopyOnWriteArrayList<>();

    private FinancialLocationContactRedaction redaction;

    @BeforeEach
    void setUp() throws Exception {
        logRecords.clear();
        redaction = build(null);
    }

    private Logger createCapturingLogger() {
        final Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(final LogRecord record) {
                logRecords.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return logger;
    }

    /**
     * Builds a redaction with the given value for the sb.redaction.ignoredfields config property.
     * A null value leaves the property unset, exercising the default list.
     */
    private FinancialLocationContactRedaction build(final String ignoredFields) throws Exception {
        final FinancialLocationContactRedaction instance = new FinancialLocationContactRedaction();
        final Field jsonDeserializerField = FinancialLocationContactRedaction.class.getDeclaredField("jsonDeserializer");
        jsonDeserializerField.setAccessible(true);
        jsonDeserializerField.set(instance, new JsonDeserializerJackson());
        final Field loggerField = FinancialLocationContactRedaction.class.getDeclaredField("logger");
        loggerField.setAccessible(true);
        loggerField.set(instance, createCapturingLogger());
        if (ignoredFields != null) {
            final Field ignoredFieldsField = FinancialLocationContactRedaction.class.getDeclaredField("ignoredFields");
            ignoredFieldsField.setAccessible(true);
            ignoredFieldsField.set(instance, Optional.of(ignoredFields));
        }
        instance.construct();
        return instance;
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

    @Test
    void testJsonEmailRedacted() {
        final String json = "{\"contact\":\"john.doe@example.com\",\"note\":\"Please reply\"}";
        final String result = redaction.sanitize(json);
        assertTrue(result.contains("{{{REDACTED-EMAIL}}}"), "Email in JSON value should be redacted");
        assertTrue(!result.contains("john.doe@example.com"), "Original email should not appear in JSON output");
        assertTrue(result.contains("\"contact\""), "JSON key should be preserved");
        assertDoesNotThrow(() -> new ObjectMapper().readTree(result), "Result should be valid JSON");
    }

    @Test
    void testJsonPhoneRedacted() {
        final String json = "{\"phone\":\"555-867-5309\",\"name\":\"Support\"}";
        final String result = redaction.sanitize(json);
        assertTrue(result.contains("{{{REDACTED-PHONE}}}"), "Phone in JSON value should be redacted");
        assertTrue(!result.contains("555-867-5309"), "Original phone should not appear in JSON output");
    }

    @Test
    void testJsonCreditCardRedacted() {
        final String json = "{\"payment\":\"4111111111111111\"}";
        final String result = redaction.sanitize(json);
        assertTrue(result.contains("{{{REDACTED-CREDIT-CARD}}}"), "Credit card in JSON value should be redacted");
        assertTrue(!result.contains("4111111111111111"), "Original credit card should not appear in JSON output");
    }

    @Test
    void testJsonNonStringValuesPreserved() {
        final String json = "{\"count\":42,\"active\":true,\"label\":\"hello\"}";
        final String result = redaction.sanitize(json);
        assertTrue(result.contains("42"), "Integer value should be preserved");
        assertTrue(result.contains("true"), "Boolean value should be preserved");
        assertTrue(result.contains("hello"), "Plain string value should be preserved");
    }

    @Test
    void testNestedJsonEmailRedacted() {
        final String json = "{\"user\":{\"email\":\"jane@example.com\",\"id\":1}}";
        final String result = redaction.sanitize(json);
        assertTrue(result.contains("{{{REDACTED-EMAIL}}}"), "Email in nested JSON value should be redacted");
        assertTrue(!result.contains("jane@example.com"), "Original email should not appear in nested JSON output");
    }

    @Test
    void testJsonArrayStringsRedacted() {
        final String json = "{\"contacts\":[\"alice@example.com\",\"bob@example.com\"]}";
        final String result = redaction.sanitize(json);
        assertTrue(!result.contains("alice@example.com"), "First email in array should be redacted");
        assertTrue(!result.contains("bob@example.com"), "Second email in array should be redacted");
    }

    @Test
    void testJsonArrayStringsRedacted2() {
        final String json = "[{\"Id\":\"blah\",\"Subject\":\"blah\",\"TextBody\":\"For more information, go to https://example.org\"}]";
        final String result = redaction.sanitize(json);
        assertTrue(!result.contains("https://example.org"), "URL in array should be redacted");
        assertDoesNotThrow(() -> new ObjectMapper().readTree(result), "Result should be valid JSON");
    }

    @Test
    void testJsonMultiplePiiTypesRedacted() {
        final String json = "{\"email\":\"john@example.com\",\"phone\":\"555-123-4567\",\"card\":\"4111111111111111\"}";
        final String result = redaction.sanitize(json);
        assertTrue(result.contains("{{{REDACTED-EMAIL}}}"), "Email in JSON should be redacted");
        assertTrue(result.contains("{{{REDACTED-PHONE}}}"), "Phone in JSON should be redacted");
        assertTrue(result.contains("{{{REDACTED-CREDIT-CARD}}}"), "Credit card in JSON should be redacted");
    }

    @Test
    void testJsonMultiplePiiTypesRedacted2() {
        final String json = "{\"email\":\"john@example.com\",\"phone\":\"555-123-4567\",\"card\":4111111111111111}";
        final String result = redaction.sanitize(json);
        assertTrue(result.contains("{{{REDACTED-EMAIL}}}"), "Email in JSON should be redacted");
        assertTrue(result.contains("{{{REDACTED-PHONE}}}"), "Phone in JSON should be redacted");
        assertTrue(result.contains("4111111111111111"), "Integer fields are retained");
        assertDoesNotThrow(() -> new ObjectMapper().readTree(result), "Result should be valid JSON");
    }

    // ---- ignored fields ----

    @Test
    void testIdFieldsNotRedacted() {
        final String json = "{\"id\":\"john.doe@example.com\",\"_id\":\"555-867-5309\",\"note\":\"mail me at jane@example.com\"}";
        final String result = redaction.sanitize(json);
        assertTrue(result.contains("john.doe@example.com"), "Value of the id field should be left alone");
        assertTrue(result.contains("555-867-5309"), "Value of the _id field should be left alone");
        assertTrue(!result.contains("jane@example.com"), "Other fields should still be redacted");
    }

    @Test
    void testIdFieldsNotRedactedCaseInsensitively() {
        final String json = "{\"Id\":\"john.doe@example.com\"}";
        final String result = redaction.sanitize(json);
        assertTrue(result.contains("john.doe@example.com"), "Field names should match regardless of case");
    }

    @Test
    void testIdFieldsNotRedactedWhenNested() {
        final String json = "{\"user\":{\"id\":\"john.doe@example.com\"},\"tickets\":[{\"_id\":\"555-867-5309\"}]}";
        final String result = redaction.sanitize(json);
        assertTrue(result.contains("john.doe@example.com"), "Nested id field should be left alone");
        assertTrue(result.contains("555-867-5309"), "Id field inside an array should be left alone");
    }

    @Test
    void testConfiguredIgnoredFieldsAreHonoured() throws Exception {
        final FinancialLocationContactRedaction configured = build(" note , _id ");
        final String json = "{\"id\":\"john.doe@example.com\",\"_id\":\"555-867-5309\",\"note\":\"mail me at jane@example.com\"}";
        final String result = configured.sanitize(json);
        assertTrue(result.contains("555-867-5309"), "Configured _id field should be left alone");
        assertTrue(result.contains("jane@example.com"), "Configured note field should be left alone");
        assertTrue(!result.contains("john.doe@example.com"), "The id field is not in the configured list, so it should be redacted");
    }

    @Test
    void testEmptyIgnoredFieldsRedactsEverything() throws Exception {
        final FinancialLocationContactRedaction configured = build("");
        final String json = "{\"id\":\"john.doe@example.com\"}";
        final String result = configured.sanitize(json);
        assertTrue(!result.contains("john.doe@example.com"), "An empty list means no field is ignored");
    }

    // ---- PII coverage ----
    // These tests guard against dependency updates silently changing what is detected.

    static Stream<Arguments> piiSamples() {
        return Stream.of(
                Arguments.of("{{{REDACTED-EMAIL}}}", "john.doe@example.com"),
                Arguments.of("{{{REDACTED-EMAIL}}}", "first.last+tag@sub.example.co.uk"),
                Arguments.of("{{{REDACTED-PHONE}}}", "555-867-5309"),
                Arguments.of("{{{REDACTED-PHONE}}}", "(555) 867-5309"),
                Arguments.of("{{{REDACTED-PHONE}}}", "555.867.5309"),
                Arguments.of("{{{REDACTED-SSN}}}", "123-45-6789"),
                Arguments.of("{{{REDACTED-CREDIT-CARD}}}", "4111111111111111"),
                Arguments.of("{{{REDACTED-CREDIT-CARD}}}", "4111 1111 1111 1111"),
                Arguments.of("{{{REDACTED-CREDIT-CARD}}}", "4111-1111-1111-1111"),
                Arguments.of("{{{REDACTED-CREDIT-CARD}}}", "5500005555555559"),
                Arguments.of("{{{REDACTED-CREDIT-CARD}}}", "378282246310005"),
                Arguments.of("{{{REDACTED-IP}}}", "192.168.1.100"),
                Arguments.of("{{{REDACTED-IP}}}", "2001:db8:85a3::8a2e:370:7334"),
                Arguments.of("{{{REDACTED-URL}}}", "https://www.example.com/path?query=1"),
                Arguments.of("{{{REDACTED-URL}}}", "http://example.org"),
                Arguments.of("{{{REDACTED-STREET-ADDRESS}}}", "123 Main Street"),
                Arguments.of("{{{REDACTED-STREET-ADDRESS}}}", "1600 Pennsylvania Avenue"),
                Arguments.of("{{{REDACTED-BANK-ROUTING-NUMBER}}}", "021000021"),
                Arguments.of("{{{REDACTED-BANK-ROUTING-NUMBER}}}", "011000015")
        );
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("piiSamples")
    void testPiiRedactedInPlainText(final String placeholder, final String pii) {
        final String result = redaction.sanitize("Here are the details: " + pii + " thanks.");
        assertTrue(result.contains(placeholder), "Expected " + placeholder + " in: " + result);
        assertFalse(result.contains(pii), "Original value should not appear in: " + result);
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("piiSamples")
    void testPiiRedactedInJsonValue(final String placeholder, final String pii) {
        final String json = "{\"details\":\"Here are the details: " + pii + " thanks.\"}";
        final String result = redaction.sanitize(json);
        assertTrue(result.contains(placeholder), "Expected " + placeholder + " in: " + result);
        assertFalse(result.contains(pii), "Original value should not appear in: " + result);
        assertDoesNotThrow(() -> new ObjectMapper().readTree(result), "Result should be valid JSON");
    }

    @Test
    void testAllPiiTypesRedactedInOneDocument() {
        final String document = """
                Customer john.doe@example.com called from 555-867-5309 about their account.
                SSN on file is 123-45-6789 and they paid with card 4111111111111111.
                The request came from 192.168.1.100 after visiting https://www.example.com/account.
                Their mailing address is 123 Main Street and the bank routing number is 021000021.
                """;
        final String result = redaction.sanitize(document);

        assertAll(
                () -> assertTrue(result.contains("{{{REDACTED-EMAIL}}}"), "Email placeholder missing"),
                () -> assertTrue(result.contains("{{{REDACTED-PHONE}}}"), "Phone placeholder missing"),
                () -> assertTrue(result.contains("{{{REDACTED-SSN}}}"), "SSN placeholder missing"),
                () -> assertTrue(result.contains("{{{REDACTED-CREDIT-CARD}}}"), "Credit card placeholder missing"),
                () -> assertTrue(result.contains("{{{REDACTED-IP}}}"), "IP placeholder missing"),
                () -> assertTrue(result.contains("{{{REDACTED-URL}}}"), "URL placeholder missing"),
                () -> assertTrue(result.contains("{{{REDACTED-STREET-ADDRESS}}}"), "Street address placeholder missing"),
                () -> assertTrue(result.contains("{{{REDACTED-BANK-ROUTING-NUMBER}}}"), "Bank routing number placeholder missing"),
                () -> assertFalse(result.contains("john.doe@example.com"), "Email leaked"),
                () -> assertFalse(result.contains("555-867-5309"), "Phone leaked"),
                () -> assertFalse(result.contains("123-45-6789"), "SSN leaked"),
                () -> assertFalse(result.contains("4111111111111111"), "Credit card leaked"),
                () -> assertFalse(result.contains("192.168.1.100"), "IP leaked"),
                () -> assertFalse(result.contains("https://www.example.com/account"), "URL leaked"),
                () -> assertFalse(result.contains("123 Main Street"), "Street address leaked"),
                () -> assertFalse(result.contains("021000021"), "Bank routing number leaked")
        );
    }

    @Test
    void testUnsanitizeDoesNotRestorePii() {
        // With no unredactions registered, unsanitizing must not bring back any of the original values
        final String result = redaction.sanitize("Email john.doe@example.com, SSN 123-45-6789, card 4111111111111111.", true);
        assertFalse(result.contains("john.doe@example.com"), "Email leaked");
        assertFalse(result.contains("123-45-6789"), "SSN leaked");
        assertFalse(result.contains("4111111111111111"), "Credit card leaked");
    }

    @Test
    void testNoErrorsLoggedForSuccessfulRedaction() {
        redaction.sanitize("Email john.doe@example.com, SSN 123-45-6789, card 4111111111111111.");
        assertTrue(logRecords.stream().noneMatch(r -> r.getLevel() == Level.SEVERE), "No redaction failures should be logged");
    }

    @Test
    void testPhileasFailureIsLoggedWithoutPii() throws Exception {
        // A filter service without a VectorService throws on every call, which is how Phileas 4.x
        // silently broke redaction when it was first upgraded
        final Field filterServiceField = FinancialLocationContactRedaction.class.getDeclaredField("filterService");
        filterServiceField.setAccessible(true);
        filterServiceField.set(redaction, new PlainTextFilterService(
                new PhileasConfiguration(new Properties()),
                new DefaultContextService(),
                null,
                null));

        redaction.sanitize("Email john.doe@example.com now.");

        final List<LogRecord> severe = logRecords.stream().filter(r -> r.getLevel() == Level.SEVERE).toList();
        assertFalse(severe.isEmpty(), "A redaction failure should be logged");
        assertTrue(severe.stream().allMatch(r -> r.getThrown() != null), "The exception should be logged");
        assertTrue(severe.stream().noneMatch(r -> r.getMessage().contains("john.doe@example.com")), "The PII should not be logged");
    }

    // ---- removeEscapeBeforePlaceholder ----

    @Test
    void testRemoveEscape_replacesEscapedOpeningBraces() {
        assertEquals("{{{REDACTED-EMAIL}}}", redaction.removeEscapeBeforePlaceholder("\\{{{REDACTED-EMAIL}}}"));
    }

    @Test
    void testRemoveEscape_replacesMultipleOccurrences() {
        final String input = "\\{{{REDACTED-EMAIL}}} and \\{{{REDACTED-PHONE}}}";
        final String expected = "{{{REDACTED-EMAIL}}} and {{{REDACTED-PHONE}}}";
        assertEquals(expected, redaction.removeEscapeBeforePlaceholder(input));
    }

    @Test
    void testRemoveEscape_noEscapeUnchanged() {
        final String input = "{{{REDACTED-EMAIL}}}";
        assertEquals(input, redaction.removeEscapeBeforePlaceholder(input));
    }

    @Test
    void testRemoveEscape_plainTextUnchanged() {
        final String input = "No PII here.";
        assertEquals(input, redaction.removeEscapeBeforePlaceholder(input));
    }

    @Test
    void testRemoveEscape_emptyStringUnchanged() {
        assertEquals("", redaction.removeEscapeBeforePlaceholder(""));
    }

    @Test
    void testRemoveEscape_backslashNotFollowedByTripleBraceUnchanged() {
        final String input = "path\\to\\file and \\{single}";
        assertEquals(input, redaction.removeEscapeBeforePlaceholder(input));
    }

    @Test
    @Disabled("Requires a specific scratch file to be present, can be enabled for manual testing")
    void testSanitizeScratchFileProducesValidJson() throws Exception {
        final Path filePath = Path.of("/Users/matthewcasperson/Library/Application Support/JetBrains/IntelliJIdea2026.1/scratches/scratch_45.json");
        final String content = Files.readString(filePath);
        final String result = redaction.sanitize(content);
        assertDoesNotThrow(() -> new ObjectMapper().readTree(result), "Sanitized scratch file should be valid JSON");
    }

    @Test
    void testSanitizeScratchFileProducesValidJson2() throws Exception {
        final String content = """
                  [ {
                  "id" : "45334534",
                  "submitter_id" : "123456789",
                  "subject" : "Rate your support experience - INC-19544: [Live webinar] The sirens of IT Ops want to drown your platform crew",
                  "organization_id" : "2387452938745",
                  "recipient" : "webinars@example.com",
                  "comments" : "",
                  "url" : "https://blah.zendesk.com/api/v2/tickets/123445.json",
                  "status" : "new",
                  "created_at" : "2026-05-31T23:40:48Z"
                }, {
                  "id" : "123124545",
                  "submitter_id" : "123123123123",
                  "subject" : "Successfully published @octopusdeploy/design-system-components@2026.3.150",
                  "recipient" : "devops@example.com",
                  "comments" : "",
                  "url" : "https://blah.zendesk.com/api/v2/tickets/123344.json",
                  "status" : "new",
                  "created_at" : "2026-05-31T23:19:25Z"
                }]
                """;
        final String result = redaction.sanitize(content);
        assertDoesNotThrow(() -> new ObjectMapper().readTree(result), "Sanitized scratch file should be valid JSON");
    }
}
