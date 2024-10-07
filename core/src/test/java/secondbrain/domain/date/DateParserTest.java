package secondbrain.domain.date;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class DateParserTest {
    @Test
    public void testParse() {
        final DateTimeFormatterBuilder dateTimeFormatterBuilder = new DateTimeFormatterBuilder()
                .append(DateTimeFormatter.ofPattern(
                        "[yyyy-MM-dd'T'HH:mmz]"
                                + "[yyyy-MM-dd'T'HH:mm:ssz]"
                                + "[yyyy-MM-dd'T'HH:mm:ss.SSSSSSz]"));
        final DateTimeFormatter dateTimeFormatter = dateTimeFormatterBuilder.toFormatter();

        var date = ZonedDateTime.parse("2021-01-01T00:00Z", dateTimeFormatter);
        assertNotNull(date);
    }

    @Test
    public void testParse3() {
        final DateTimeFormatterBuilder dateTimeFormatterBuilder = new DateTimeFormatterBuilder()
                .append(DateTimeFormatter.ofPattern(
                        "[yyyy-MM-dd'T'HH:mmz]"
                                + "[yyyy-MM-dd'T'HH:mm:ssz]"
                                + "[yyyy-MM-dd'T'HH:mm:ss.SSSSSSz]"));
        final DateTimeFormatter dateTimeFormatter = dateTimeFormatterBuilder.toFormatter();

        var date = ZonedDateTime.parse("2024-09-07T21:27:17.384297Z", dateTimeFormatter);
        assertNotNull(date);
    }

    @Test
    public void testParse2() {
        final DateTimeFormatterBuilder dateTimeFormatterBuilder = new DateTimeFormatterBuilder()
                .append(DateTimeFormatter.ofPattern(
                        "[yyyy-MM-dd'T'HH:mm:ss.SSSSSS]"
                        + "[yyyy-MM-dd'T'HH:mm:ss.SSSS]"
                                + "[yyyy-MM-dd'T'HH:mm:ss]"
                                + "[yyyy-MM-dd'T'HH:mm]"));
        final DateTimeFormatter dateTimeFormatter = dateTimeFormatterBuilder.toFormatter();

        var date = LocalDateTime.parse("2024-10-07T21:37:25.6822", dateTimeFormatter);
        assertNotNull(date);
    }
}
