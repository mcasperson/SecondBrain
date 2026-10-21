package secondbrain.domain.converter;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Two test converters with the formats "upper" and "lower" are registered alongside the NoOpStringConverter.
 */
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(StringConverterSelector.class)
@AddBeanClasses(NoOpStringConverter.class)
@AddBeanClasses(StringConverterSelectorTest.UpperStringConverter.class)
@AddBeanClasses(StringConverterSelectorTest.LowerStringConverter.class)
class StringConverterSelectorTest {

    @Inject
    private StringConverterSelector selector;

    @Test
    void getStringConverterReturnsTheConverterMatchingTheFormat() {
        final StringConverter converter = selector.getStringConverter("upper");

        assertEquals("upper", converter.getFormat());
        assertEquals("HELLO", converter.convert("Hello"));
    }

    @Test
    void getStringConverterDistinguishesBetweenRegisteredFormats() {
        final StringConverter converter = selector.getStringConverter("lower");

        assertEquals("lower", converter.getFormat());
        assertEquals("hello", converter.convert("Hello"));
    }

    @Test
    void getStringConverterFallsBackToNoOpForUnknownFormat() {
        final StringConverter converter = selector.getStringConverter("unknown");

        assertEquals("no-op", converter.getFormat());
        assertEquals("Hello", converter.convert("Hello"));
    }

    @Test
    void getStringConverterMatchesFormatCaseSensitively() {
        assertEquals("no-op", selector.getStringConverter("UPPER").getFormat());
    }

    @Test
    void getStringConverterReturnsNoOpForNoOpFormat() {
        assertEquals("no-op", selector.getStringConverter("no-op").getFormat());
    }

    @ApplicationScoped
    static class UpperStringConverter implements StringConverter {
        @Override
        public String getFormat() {
            return "upper";
        }

        @Override
        public String convert(final String response) {
            return response.toUpperCase();
        }
    }

    @ApplicationScoped
    static class LowerStringConverter implements StringConverter {
        @Override
        public String getFormat() {
            return "lower";
        }

        @Override
        public String convert(final String response) {
            return response.toLowerCase();
        }
    }
}
