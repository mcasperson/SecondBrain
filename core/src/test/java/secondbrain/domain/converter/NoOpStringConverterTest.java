package secondbrain.domain.converter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NoOpStringConverterTest {

    private final NoOpStringConverter converter = new NoOpStringConverter();

    @Test
    void getFormatReturnsNoOp() {
        assertEquals("no-op", converter.getFormat());
    }

    @Test
    void convertReturnsTheInputUnchanged() {
        final String input = "  Some *markdown* text\nwith lines  ";

        assertSame(input, converter.convert(input));
    }

    @Test
    void convertReturnsEmptyStringForEmptyInput() {
        assertEquals("", converter.convert(""));
    }
}
