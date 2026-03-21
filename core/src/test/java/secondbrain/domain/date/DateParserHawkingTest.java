package secondbrain.domain.date;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DateParserHawkingTest {
    private final DateParserHawking dateParser = new DateParserHawking();

    @Test
    void testParseDateWithConfiguredDayMonthYearFormat() {
        final ZoneId systemZone = ZoneId.systemDefault();

        final ZonedDateTime date = dateParser.parseDate("09/08/2024 07:00");

        assertEquals(ZonedDateTime.of(2024, 9, 8, 7, 0, 0, 0, systemZone), date);
    }

    @Test
    void testParseDateSupportsNaturalLanguageInput() {
        final ZoneId systemZone = ZoneId.systemDefault();

        final ZonedDateTime date = dateParser.parseDate("Sep 8 2024 7:00 PM");

        assertEquals(ZonedDateTime.of(2024, 9, 8, 19, 0, 0, 0, systemZone), date);
    }

    @Test
    void testParseDateUsesCurrentSystemTimeZone() {
        final ZoneId systemZone = ZoneId.systemDefault();

        final ZonedDateTime date = dateParser.parseDate("09/08/2024 07:00");

        assertEquals(systemZone, date.getZone());
    }

    @Test
    void testParseDateRejectsBlankString() {
        final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> dateParser.parseDate("  "));

        assertEquals("date can not be an empty string", exception.getMessage());
    }

    @Test
    void testParseDateRejectsInvalidDate() {
        final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> dateParser.parseDate("invalid-date"));

        assertEquals("Unable to parse date: invalid-date", exception.getMessage());
    }
}

