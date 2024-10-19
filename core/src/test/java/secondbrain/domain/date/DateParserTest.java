package secondbrain.domain.date;

import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class DateParserTest {

    @Test
    public void testParseZonedDate() {
        DateParserIso8601 dateParser = new DateParserIso8601();
        ZonedDateTime date = dateParser.parseDate("2024-09-08T07:00:54.455239138Z");
        assertNotNull(date);
    }

    @Test
    public void testParseZonedDateWithOffset() {
        DateParserIso8601 dateParser = new DateParserIso8601();
        ZonedDateTime date = dateParser.parseDate("2024-09-08T07:00:54.455239138+02:00");
        assertNotNull(date);
    }

    @Test
    public void testParseLocalDate() {
        DateParserIso8601 dateParser = new DateParserIso8601();
        ZonedDateTime date = dateParser.parseDate("2024-09-08T07:00:54.455239138");
        assertNotNull(date);
    }

    @Test
    public void testParseLocalDateWithoutNanoseconds() {
        DateParserIso8601 dateParser = new DateParserIso8601();
        ZonedDateTime date = dateParser.parseDate("2024-09-08T07:00:54");
        assertNotNull(date);
    }

    @Test
    public void testParseLocalDateWithoutSeconds() {
        DateParserIso8601 dateParser = new DateParserIso8601();
        ZonedDateTime date = dateParser.parseDate("2024-09-08T07:00");
        assertNotNull(date);
    }

    @Test
    public void testParseInvalidDate() {
        DateParserIso8601 dateParser = new DateParserIso8601();
        assertThrows(Exception.class, () -> dateParser.parseDate("invalid-date"));
    }
}