package secondbrain.domain.converter;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(SimpleMarkdnParser.class)
@AddBeanClasses(MarkdnParser.class)
public class SimpleMarkdnParserTest {

    @Inject
    SimpleMarkdnParser simpleMarkdnParser;

    @Test
    public void testConvertRemovesUrlMarkup() {
        String input = "Check out this link: <https://example.com|Example>";
        String expected = "Check out this link: https://example.com";

        String actual = simpleMarkdnParser.convert(input);

        assertEquals(expected, actual);
    }

    @Test
    public void testConvertWithMultipleUrls() {
        String input = "<https://example.com|Example> and <http://test.com|Test Site>";
        String expected = "https://example.com and http://test.com";

        String actual = simpleMarkdnParser.convert(input);

        assertEquals(expected, actual);
    }

    @Test
    public void testConvertWithNoUrls() {
        String input = "Plain text with **bold** and *italic*";
        String expected = "Plain text with *bold* and _italic_";

        String actual = simpleMarkdnParser.convert(input);

        assertEquals(expected, actual);
    }

    @Test
    public void testConvertWithEmptyString() {
        String input = "";
        String expected = "";

        String actual = simpleMarkdnParser.convert(input);

        assertEquals(expected, actual);
    }
}