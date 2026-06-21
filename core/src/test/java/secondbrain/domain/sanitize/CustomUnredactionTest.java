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
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.test.TestConfigUtil;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@SuppressWarnings("NullAway")
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(CustomUnredaction.class)
@AddBeanClasses(Loggers.class)
class CustomUnredactionTest {

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
}

