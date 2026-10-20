package secondbrain.domain.date;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the default methods of {@link DateParser}. The interface has no injected dependencies, so it is implemented
 * with lambdas rather than CDI beans.
 */
class DateParserDefaultMethodsTest {
    private static final ZonedDateTime PARSED = ZonedDateTime.of(2024, 9, 8, 7, 0, 54, 0, ZoneOffset.UTC);
    private static final ZonedDateTime FALLBACK = ZonedDateTime.of(2000, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC);

    private final List<String> parsedDates = new CopyOnWriteArrayList<>();

    private final DateParser successfulParser = date -> {
        parsedDates.add(date);
        return PARSED;
    };

    private final DateParser failingParser = date -> {
        parsedDates.add(date);
        throw new IllegalArgumentException("Unparseable date: " + date);
    };

    @Test
    void parseDateOrDefaultReturnsTheParsedDate() {
        assertEquals(PARSED, successfulParser.parseDateOrDefault("2024-09-08T07:00:54Z", FALLBACK));
        assertEquals(List.of("2024-09-08T07:00:54Z"), parsedDates);
    }

    @Test
    void parseDateOrDefaultReturnsTheDefaultWhenParsingFails() {
        assertSame(FALLBACK, failingParser.parseDateOrDefault("not a date", FALLBACK));
        assertEquals(List.of("not a date"), parsedDates);
    }

    @Test
    void parseDateOrDefaultReturnsANullDefaultWhenParsingFails() {
        assertNull(failingParser.parseDateOrDefault("not a date", null));
    }

    @Test
    void parseDateOrDefaultIgnoresTheDefaultWhenParsingSucceeds() {
        assertEquals(PARSED, successfulParser.parseDateOrDefault("2024-09-08T07:00:54Z", null));
    }

    @Test
    void parseDateOrDefaultWorksWithARealParser() {
        final DateParser parser = ZonedDateTime::parse;

        assertEquals(PARSED, parser.parseDateOrDefault("2024-09-08T07:00:54Z", FALLBACK));
        assertSame(FALLBACK, parser.parseDateOrDefault("2024-13-45", FALLBACK));
    }

    @Test
    void parseDateOrDefaultWithoutADefaultReturnsTheParsedDate() {
        assertEquals(PARSED, successfulParser.parseDateOrDefault("2024-09-08T07:00:54Z"));
        assertEquals(List.of("2024-09-08T07:00:54Z"), parsedDates);
    }

    @Test
    void parseDateOrDefaultWithoutADefaultReturnsTheEpochInTheSystemZoneWhenParsingFails() {
        final ZonedDateTime result = failingParser.parseDateOrDefault("not a date");

        assertEquals(ZonedDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneId.systemDefault()), result);
        assertEquals(ZoneId.systemDefault(), result.getZone());
        assertEquals(List.of("not a date"), parsedDates);
    }
}
