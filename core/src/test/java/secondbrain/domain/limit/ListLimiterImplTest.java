package secondbrain.domain.limit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ListLimiterImplTest {

    @Test
    public void testLimitListContentWithinLimit() {
        ListLimiter listLimiter = new ListLimiterAtomicCutOff();
        List<String> inputList = List.of("one", "two", "three");
        int limit = 15;

        List<String> result = listLimiter.limitListContent(inputList, limit);

        assertEquals(inputList, result, "The list should remain unchanged when within the limit");
    }

    @Test
    public void testLimitListContentExceedsLimit() {
        ListLimiter listLimiter = new ListLimiterAtomicCutOff();
        List<String> inputList = List.of("one", "two", "three");
        int limit = 6;

        List<String> result = listLimiter.limitListContent(inputList, limit);

        List<String> expectedList = List.of("one", "two");
        assertEquals(expectedList, result, "The list should be truncated to fit within the limit");
    }

    @Test
    public void testLimitListContentExactLimit() {
        ListLimiter listLimiter = new ListLimiterAtomicCutOff();
        List<String> inputList = List.of("one", "two", "three");
        int limit = 11;

        List<String> result = listLimiter.limitListContent(inputList, limit);

        assertEquals(inputList, result, "The list should remain unchanged when exactly at the limit");
    }

    @Test
    public void testLimitListContentByFraction() {
        ListLimiter listLimiter = new ListLimiterAtomicCutOff();
        List<String> inputList = List.of("one", "two", "three");

        List<String> result = listLimiter.limitListContentByFraction(inputList, x -> x, 0.5f);

        assertEquals(1, result.size(), "The list should have 1 item when limited by 50%");
    }

    @Test
    public void testLimitListContentByFractionTwo() {
        ListLimiter listLimiter = new ListLimiterAtomicCutOff();
        List<String> inputList = List.of("one", "two", "three");

        List<String> result = listLimiter.limitListContentByFraction(inputList, x -> x, 0.75f);

        assertEquals(2, result.size(), "The list should have 2 items when limited by 75%");
    }

    @Test
    public void testLimitListContentByFractionThree() {
        ListLimiter listLimiter = new ListLimiterAtomicCutOff();
        List<String> inputList = List.of("1", "2", "3", "4");

        List<String> result = listLimiter.limitListContentByFraction(inputList, x -> x, 0.5f);

        assertEquals(2, result.size(), "The list should have 2 items when limited by 50%");
    }

    @Test
    public void testLimitListContentByFractionFour() {
        ListLimiter listLimiter = new ListLimiterAtomicCutOff();
        List<String> inputList = List.of("one", "two", "3", "4");

        List<String> result = listLimiter.limitListContentByFraction(inputList, x -> x, 0.5f);

        assertEquals(1, result.size(), "The list should have 1 items when limited by 50%");
    }
}