package secondbrain.domain.sanitize;

import io.smallrye.common.annotation.Identifier;
import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import secondbrain.domain.json.JsonDeserializerJackson;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.test.TestConfigUtil;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@SuppressWarnings("NullAway")
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(CustomUnredaction.class)
@AddBeanClasses(Loggers.class)
class CustomUnredactionTest {
    private static final String SALESFORCE_ID = "006Qq5552671OPyIAM";

    @Inject
    private CustomUnredaction unredaction;

    @BeforeAll
    static void registerConfig() {
        TestConfigUtil.registerConfig(Map.of("sb.unredaction.regex1", "ACC-\\d+"));
    }

    @Produces
    @Identifier("financialLocationContactRedaction")
    @ApplicationScoped
    SanitizeDocument produceAccountSanitizer() {
        return new SanitizeDocument() {
            @Override
            public String sanitize(final String document) {
                return document == null ? "" : document.replaceAll("ACC-\\d+", "{{{ACCOUNT}}}");
            }

            @Override
            public String sanitize(final String document, final boolean unsanitize) {
                return sanitize(document);
            }
        };
    }

    @Test
    void testUnredactRestoresSanitizedMatch() {
        final String original = "Customer account ACC-123 was charged.";
        final String redacted = "Customer account {{{ACCOUNT}}} was charged.";

        assertEquals(original, unredaction.unredact(original, redacted));
    }

    @Test
    void testUnredactUsesLastMatchWhenMultipleValuesMapToSamePlaceholder() {
        final String original = "Customer IDs: ACC-123 and ACC-456";
        final String redacted = "Customer IDs: {{{ACCOUNT}}} and {{{ACCOUNT}}}";

        assertEquals(original, unredaction.unredact(original, redacted));
    }

    @Test
    void testUnredactReturnsRedactedWhenInputBlank() {
        assertEquals("already redacted", unredaction.unredact("", "already redacted"));
        assertEquals("already redacted", unredaction.unredact("Customer ACC-123", "already redacted"));
        assertNull(unredaction.unredact("Customer ACC-123", null));
    }

    /**
     * Salesforce IDs can contain runs of digits that the real redaction mistakes for phone numbers,
     * e.g. 006Qq{{{REDACTED-PHONE}}}OPyIAM. A word-bounded regex must restore them.
     */
    @Test
    void testUnredactRestoresSalesforceIdRedactedAsPhoneNumber() throws Exception {
        final FinancialLocationContactRedaction redaction = buildRealRedaction();
        final CustomUnredaction salesforceUnredaction = buildUnredaction(
                "\\b(?:001|003|006|00Q|500)[a-zA-Z0-9]{12}(?:[a-zA-Z0-9]{3})?\\b", redaction);

        final String original = "{\"accountId\":\"" + SALESFORCE_ID + "\",\"subject\":\"Renewal\"}";
        final String redacted = redaction.sanitize(original, false);

        // Guard against the redaction no longer flagging the ID, which would make this test meaningless
        assertNotEquals(original, redacted);
        assertEquals(original, salesforceUnredaction.unredact(original, redacted));
    }

    /**
     * The regex is applied to the whole document, so ^ and $ anchors only match when the
     * document consists of nothing but the ID.
     */
    @Test
    void testUnredactWithAnchoredRegexDoesNotRestoreIdInsideDocument() throws Exception {
        final FinancialLocationContactRedaction redaction = buildRealRedaction();
        final CustomUnredaction anchoredUnredaction = buildUnredaction("^[a-zA-Z0-9]{15,24}$", redaction);

        final String original = "{\"accountId\":\"" + SALESFORCE_ID + "\",\"subject\":\"Renewal\"}";
        final String redacted = redaction.sanitize(original, false);

        assertNotEquals(original, anchoredUnredaction.unredact(original, redacted));
    }

    private static FinancialLocationContactRedaction buildRealRedaction() throws Exception {
        final FinancialLocationContactRedaction instance = new FinancialLocationContactRedaction();
        setField(instance, "jsonDeserializer", new JsonDeserializerJackson());
        instance.construct();
        return instance;
    }

    private static CustomUnredaction buildUnredaction(final String regex, final SanitizeDocument sanitizeDocument) throws Exception {
        final CustomUnredaction instance = new CustomUnredaction();
        setField(instance, "regex1", Optional.of(regex));
        setField(instance, "maxMatches", Optional.empty());
        setField(instance, "sanitizeDocument", sanitizeDocument);
        setField(instance, "logger", Logger.getLogger(CustomUnredactionTest.class.getName()));
        instance.init();
        return instance;
    }

    private static void setField(final Object target, final String name, final Object value) throws Exception {
        final Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
