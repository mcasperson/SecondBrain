package secondbrain.domain.date;

import org.junit.jupiter.api.Test;
import secondbrain.domain.date.impl.DateParserImpl;

import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class DateParserTest {

    @Test
    public void testParseZonedDate() {
        DateParserImpl dateParser = new DateParserImpl();
        ZonedDateTime date = dateParser.parseDate("2024-09-08T07:00:54.455239138Z");
        assertNotNull(date);
    }

    @Test
    public void testParseZonedDateWithOffset() {
        DateParserImpl dateParser = new DateParserImpl();
        ZonedDateTime date = dateParser.parseDate("2024-09-08T07:00:54.455239138+02:00");
        assertNotNull(date);
    }

    @Test
    public void testParseLocalDate() {
        DateParserImpl dateParser = new DateParserImpl();
        ZonedDateTime date = dateParser.parseDate("2024-09-08T07:00:54.455239138");
        assertNotNull(date);
    }

    @Test
    public void testParseLocalDateWithoutNanoseconds() {
        DateParserImpl dateParser = new DateParserImpl();
        ZonedDateTime date = dateParser.parseDate("2024-09-08T07:00:54");
        assertNotNull(date);
    }

    @Test
    public void testParseLocalDateWithoutSeconds() {
        DateParserImpl dateParser = new DateParserImpl();
        ZonedDateTime date = dateParser.parseDate("2024-09-08T07:00");
        assertNotNull(date);
    }

    @Test
    public void testParseInvalidDate() {
        DateParserImpl dateParser = new DateParserImpl();
        assertThrows(Exception.class, () -> dateParser.parseDate("invalid-date"));
    }
}