package secondbrain.domain.date;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;

import static org.junit.jupiter.api.Assertions.*;

class DateParserYyyyMmDdTest {

    private final DateParserYyyyMmDd dateParser = new DateParserYyyyMmDd();

    @Test
    void testParsesValidDate() {
        final ZonedDateTime date = dateParser.parseDate("2024-09-08");

        assertEquals(2024, date.getYear());
        assertEquals(9, date.getMonthValue());
        assertEquals(8, date.getDayOfMonth());
    }

    @Test
    void testResultIsAtMidnight() {
        final ZonedDateTime date = dateParser.parseDate("2024-09-08");

        assertEquals(0, date.getHour());
        assertEquals(0, date.getMinute());
        assertEquals(0, date.getSecond());
    }

    @Test
    void testResultUsesSystemDefaultTimeZone() {
        final ZonedDateTime date = dateParser.parseDate("2024-09-08");

        assertEquals(ZoneId.systemDefault(), date.getZone());
    }

    @Test
    void testParsesFirstDayOfYear() {
        final ZonedDateTime date = dateParser.parseDate("2024-01-01");

        assertEquals(2024, date.getYear());
        assertEquals(1, date.getMonthValue());
        assertEquals(1, date.getDayOfMonth());
    }

    @Test
    void testParsesLastDayOfYear() {
        final ZonedDateTime date = dateParser.parseDate("2024-12-31");

        assertEquals(2024, date.getYear());
        assertEquals(12, date.getMonthValue());
        assertEquals(31, date.getDayOfMonth());
    }

    @Test
    void testRejectsDateWithTime() {
        assertThrows(DateTimeParseException.class, () -> dateParser.parseDate("2024-09-08T07:00:00"));
    }

    @Test
    void testRejectsInvalidFormat() {
        assertThrows(DateTimeParseException.class, () -> dateParser.parseDate("09/08/2024"));
    }

    @Test
    void testRejectsInvalidDate() {
        assertThrows(DateTimeParseException.class, () -> dateParser.parseDate("2024-13-01"));
    }

    @Test
    void testRejectsBlankString() {
        assertThrows(DateTimeParseException.class, () -> dateParser.parseDate(""));
    }
}

