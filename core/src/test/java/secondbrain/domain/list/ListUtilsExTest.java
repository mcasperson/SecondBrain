package secondbrain.domain.list;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListUtilsExTest {

    @Test
    void testSafeSubList_NullList() {
        List<String> result = ListUtilsEx.safeSubList(null, 0, 5);
        assertTrue(result.isEmpty(), "Expected empty list for null input list");
    }

    @Test
    void testSafeSubList_FromIndexGreaterThanSize() {
        List<String> list = List.of("a", "b", "c");
        List<String> result = ListUtilsEx.safeSubList(list, 5, 10);
        assertTrue(result.isEmpty(), "Expected empty list when fromIndex is greater than list size");
    }

    @Test
    void testSafeSubList_ToIndexLessThanOrEqualToZero() {
        List<String> list = List.of("a", "b", "c");
        List<String> result = ListUtilsEx.safeSubList(list, 0, 0);
        assertTrue(result.isEmpty(), "Expected empty list when toIndex is less than or equal to zero");
    }

    @Test
    void testSafeSubList_FromIndexGreaterThanOrEqualToToIndex() {
        List<String> list = List.of("a", "b", "c");
        List<String> result = ListUtilsEx.safeSubList(list, 2, 2);
        assertTrue(result.isEmpty(), "Expected empty list when fromIndex is greater than or equal to toIndex");
    }

    @Test
    void testSafeSubList_NormalCase() {
        List<String> list = List.of("a", "b", "c", "d", "e");
        List<String> result = ListUtilsEx.safeSubList(list, 1, 4);
        assertEquals(List.of("b", "c", "d"), result, "Expected sublist from index 1 to 4");
    }

    @Test
    void testSafeSubList_FromIndexLessThanZero() {
        List<String> list = List.of("a", "b", "c", "d", "e");
        List<String> result = ListUtilsEx.safeSubList(list, -1, 3);
        assertEquals(List.of("a", "b", "c"), result, "Expected sublist from index 0 to 3 when fromIndex is less than zero");
    }

    @Test
    void testSafeSubList_ToIndexGreaterThanSize() {
        List<String> list = List.of("a", "b", "c", "d", "e");
        List<String> result = ListUtilsEx.safeSubList(list, 2, 10);
        assertEquals(List.of("c", "d", "e"), result, "Expected sublist from index 2 to end of list when toIndex is greater than list size");
    }
}