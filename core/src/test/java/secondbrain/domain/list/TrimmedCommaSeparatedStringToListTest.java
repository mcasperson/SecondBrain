package secondbrain.domain.list;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TrimmedCommaSeparatedStringToListTest {

    private final TrimmedCommaSeparatedStringToList converter = new TrimmedCommaSeparatedStringToList();

    @Test
    void testConvertNormalCase() {
        List<String> result = converter.convert("a, b, c");
        assertEquals(List.of("a", "b", "c"), result);
    }

    @Test
    void testConvertNormalCaseLineBreak() {
        List<String> result = converter.convert("a, \nb, c");
        assertEquals(List.of("a", "b", "c"), result);
    }

    @Test
    void testConvertNormalCaseLineBreakMiddle() {
        List<String> result = converter.convert("a, \nb\nc, d");
        assertEquals(List.of("a", "b\nc", "d"), result);
    }

    @Test
    void testConvertExtraSpacesAndEmptyItems() {
        List<String> result = converter.convert("  a , , b ,   ,c  ");
        assertEquals(List.of("a", "b", "c"), result);
    }

    @Test
    void testConvertEmptyString() {
        List<String> result = converter.convert("");
        assertEquals(List.of(), result);
    }

    @Test
    void testConvertNullInput() {
        List<String> result = converter.convert(null);
        assertEquals(List.of(), result);
    }

    @Test
    void testConvertOnlyCommas() {
        List<String> result = converter.convert(",,,");
        assertEquals(List.of(), result);
    }
}
