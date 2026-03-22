package secondbrain.domain.date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

class DateParserEverythingTest {

    private DateParserEverything dateParser;

    @BeforeEach
    void setUp() throws Exception {
        dateParser = new DateParserEverything();
        setField("dateParserHawking", new DateParserHawking());
        setField("dateParserIso8601", new DateParserIso8601());
        setField("dateParserUnix", new DateParserUnix());
        setField("dateParserYyyyMmDd", new DateParserYyyyMmDd());
    }

    private void setField(final String name, final Object value) throws Exception {
        final Field field = DateParserEverything.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(dateParser, value);
    }

    @Test
    void testParseUnixTimestamp() {
        // 1725782454 → 2024-09-08T07:00:54 UTC
        final ZonedDateTime date = dateParser.parseDate("1725782454");

        assertNotNull(date);
        assertEquals(1725782454L, date.toEpochSecond());
    }

    @Test
    void testParseRelativeDate() {
        // 1725782454 → 2024-09-08T07:00:54 UTC
        final ZonedDateTime date = dateParser.parseDate("two weeks ago");

        assertNotNull(date);
    }

    @Test
    void testParseIso8601DateWithTimezone() {
        final ZonedDateTime date = dateParser.parseDate("2024-09-08T07:00:54.455239138Z");

        assertNotNull(date);
        assertEquals(2024, date.getYear());
        assertEquals(9, date.getMonthValue());
        assertEquals(8, date.getDayOfMonth());
        assertEquals(7, date.getHour());
    }

    @Test
    void testParseYyyyMmDDateWithTimezone() {
        final ZonedDateTime date = dateParser.parseDate("2024-09-08");

        assertNotNull(date);
        assertEquals(2024, date.getYear());
        assertEquals(9, date.getMonthValue());
        assertEquals(8, date.getDayOfMonth());
    }

    @Test
    void testParseIso8601DateWithOffset() {
        final ZonedDateTime date = dateParser.parseDate("2024-09-08T07:00:54+02:00");

        assertNotNull(date);
        assertEquals(2024, date.getYear());
        assertEquals(9, date.getMonthValue());
        assertEquals(8, date.getDayOfMonth());
    }

    @Test
    void testParseIso8601DateWithoutTimezone() {
        final ZonedDateTime date = dateParser.parseDate("2024-09-08T07:00:54");

        assertNotNull(date);
        assertEquals(2024, date.getYear());
        assertEquals(9, date.getMonthValue());
        assertEquals(8, date.getDayOfMonth());
        assertEquals(7, date.getHour());
    }

    @Test
    void testParseNaturalLanguageDate() {
        final ZoneId systemZone = ZoneId.systemDefault();

        final ZonedDateTime date = dateParser.parseDate("Sep 8 2024 7:00 PM");

        assertEquals(ZonedDateTime.of(2024, 9, 8, 19, 0, 0, 0, systemZone), date);
    }

    @Test
    void testParseNaturalLanguageDateWithMmDdYyyyFormat() {
        final ZoneId systemZone = ZoneId.systemDefault();

        final ZonedDateTime date = dateParser.parseDate("09/08/2024 07:00");

        assertEquals(ZonedDateTime.of(2024, 9, 8, 7, 0, 0, 0, systemZone), date);
    }

    @Test
    void testParseDateUsesSystemTimeZoneForNaturalLanguage() {
        final ZoneId systemZone = ZoneId.systemDefault();

        final ZonedDateTime date = dateParser.parseDate("Sep 8 2024 7:00 PM");

        assertEquals(systemZone, date.getZone());
    }

    @Test
    void testParseInvalidDateThrowsException() {
        assertThrows(Exception.class, () -> dateParser.parseDate("not-a-date-at-all!!"));
    }
}

