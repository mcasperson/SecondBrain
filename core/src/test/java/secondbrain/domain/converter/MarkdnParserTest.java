package secondbrain.domain.converter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class MarkdnParserTest {
    @Test
    public void testPrintMarkDn() {
        MarkdnParser parser = new MarkdnParser();
        String response = "**bold text** and some other text";
        String expected = "*bold text* and some other text";
        String actual = parser.printMarkDn(response);
        assertEquals(expected, actual);
    }
}