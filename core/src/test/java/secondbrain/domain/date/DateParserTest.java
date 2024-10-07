package secondbrain.domain.date;

import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class DateParserTest {

    @Test
    public void testParseZonedDate() {
        DateParser dateParser = new DateParser();
        ZonedDateTime date = dateParser.parseDate("2024-09-08T07:00:54.455239138Z");
        assertNotNull(date);
    }

    @Test
    public void testParseZonedDateWithOffset() {
        DateParser dateParser = new DateParser();
        ZonedDateTime date = dateParser.parseDate("2024-09-08T07:00:54.455239138+02:00");
        assertNotNull(date);
    }

    @Test
    public void testParseLocalDate() {
        DateParser dateParser = new DateParser();
        ZonedDateTime date = dateParser.parseDate("2024-09-08T07:00:54.455239138");
        assertNotNull(date);
    }

    @Test
    public void testParseLocalDateWithoutNanoseconds() {
        DateParser dateParser = new DateParser();
        ZonedDateTime date = dateParser.parseDate("2024-09-08T07:00:54");
        assertNotNull(date);
    }

    @Test
    public void testParseLocalDateWithoutSeconds() {
        DateParser dateParser = new DateParser();
        ZonedDateTime date = dateParser.parseDate("2024-09-08T07:00");
        assertNotNull(date);
    }

    @Test
    public void testParseInvalidDate() {
        DateParser dateParser = new DateParser();
        assertThrows(Exception.class, () -> dateParser.parseDate("invalid-date"));
    }
}