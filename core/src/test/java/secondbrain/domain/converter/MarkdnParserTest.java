package secondbrain.domain.converter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class MarkdnParserTest {
    @Test
    public void testConvert() {
        MarkdnParser parser = new MarkdnParser();
        String response = "**bold text** and some other text";
        String expected = "*bold text* and some other text";
        String actual = parser.convert(response);
        assertEquals(expected, actual);
    }
}