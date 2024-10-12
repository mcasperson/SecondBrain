package secondbrain.domain.limit;

import org.junit.jupiter.api.Test;
import secondbrain.domain.limit.impl.ListLimiterImpl;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ListLimiterImplTest {

    @Test
    public void testLimitListContentWithinLimit() {
        ListLimiter listLimiter = new ListLimiterImpl();
        List<String> inputList = List.of("one", "two", "three");
        int limit = 15;

        List<String> result = listLimiter.limitListContent(inputList, limit);

        assertEquals(inputList, result, "The list should remain unchanged when within the limit");
    }

    @Test
    public void testLimitListContentExceedsLimit() {
        ListLimiter listLimiter = new ListLimiterImpl();
        List<String> inputList = List.of("one", "two", "three");
        int limit = 6;

        List<String> result = listLimiter.limitListContent(inputList, limit);

        List<String> expectedList = List.of("one", "two");
        assertEquals(expectedList, result, "The list should be truncated to fit within the limit");
    }

    @Test
    public void testLimitListContentExactLimit() {
        ListLimiter listLimiter = new ListLimiterImpl();
        List<String> inputList = List.of("one", "two", "three");
        int limit = 11;

        List<String> result = listLimiter.limitListContent(inputList, limit);

        assertEquals(inputList, result, "The list should remain unchanged when exactly at the limit");
    }
}