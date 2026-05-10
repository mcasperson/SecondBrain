package secondbrain.domain.sanitize;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@SuppressWarnings("NullAway")
class CustomUnredactionTest {

    @Test
    void testUnredactRestoresSanitizedMatch() throws Exception {
        final CustomUnredaction unredaction = new CustomUnredaction();
        setField(unredaction, "regex1", Optional.of("ACC-\\d+"));
        setField(unredaction, "sanitizeDocument", accountSanitizer());

        final String original = "Customer account ACC-123 was charged.";
        final String redacted = "Customer account {{{ACCOUNT}}} was charged.";

        assertEquals(original, unredaction.unredact(original, redacted));
    }

    @Test
    void testUnredactUsesLastMatchWhenMultipleValuesMapToSamePlaceholder() throws Exception {
        final CustomUnredaction unredaction = new CustomUnredaction();
        setField(unredaction, "regex1", Optional.of("ACC-\\d+"));
        setField(unredaction, "sanitizeDocument", accountSanitizer());

        final String original = "Customer IDs: ACC-123 and ACC-456";
        final String redacted = "Customer IDs: {{{ACCOUNT}}} and {{{ACCOUNT}}}";

        assertEquals(original, unredaction.unredact(original, redacted));
    }

    @Test
    void testUnredactReturnsRedactedWhenInputBlank() throws Exception {
        final CustomUnredaction unredaction = new CustomUnredaction();
        setField(unredaction, "regex1", Optional.of("ACC-\\d+"));
        setField(unredaction, "sanitizeDocument", accountSanitizer());

        assertEquals("already redacted", unredaction.unredact("", "already redacted"));
        assertEquals("already redacted", unredaction.unredact("Customer ACC-123", "already redacted"));
        assertNull(unredaction.unredact("Customer ACC-123", null));
    }

    @Test
    void testUnredactReturnsRedactedWhenRegexMissing() throws Exception {
        final CustomUnredaction unredaction = new CustomUnredaction();
        setField(unredaction, "regex1", Optional.empty());
        setField(unredaction, "sanitizeDocument", accountSanitizer());

        final String redacted = "Customer account {{{ACCOUNT}}} was charged.";

        assertEquals(redacted, unredaction.unredact("Customer account ACC-123 was charged.", redacted));
    }

    @Test
    void testUnredactReturnsRedactedWhenRegexInvalid() throws Exception {
        final CustomUnredaction unredaction = new CustomUnredaction();
        setField(unredaction, "regex1", Optional.of("[invalid"));
        setField(unredaction, "sanitizeDocument", accountSanitizer());

        final String redacted = "Customer account {{{ACCOUNT}}} was charged.";

        assertEquals(redacted, unredaction.unredact("Customer account ACC-123 was charged.", redacted));
    }

    private static SanitizeDocument accountSanitizer() {
        return document -> document == null ? "" : document.replaceAll("ACC-\\d+", "{{{ACCOUNT}}}");
    }

    private static void setField(final Object target, final String fieldName, final Object value) throws Exception {
        final Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}

