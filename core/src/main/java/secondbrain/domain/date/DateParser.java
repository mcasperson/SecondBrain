package secondbrain.domain.date;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.chrono.ChronoLocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;

@ApplicationScoped
public class DateParser {
    public ZonedDateTime parseDate(@NotNull final String date) {
        try {
            return parseZonedDate(date);
        } catch (DateTimeParseException e) {
            return parseLocalDate(date);
        }
    }

    public ZonedDateTime parseZonedDate(@NotNull final String date) {
        final DateTimeFormatterBuilder dateTimeFormatterBuilder = new DateTimeFormatterBuilder()
                .append(DateTimeFormatter.ofPattern(
                        "[yyyy-MM-dd'T'HH:mmz]"
                                + "[yyyy-MM-dd'T'HH:mm:ssz]"
                                + "[yyyy-MM-dd'T'HH:mm:ss.SSSSSSz]"
                                + "[yyyy-MM-dd'T'HH:mm:ss.SSSSz]"
                ));
        final DateTimeFormatter dateTimeFormatter = dateTimeFormatterBuilder.toFormatter();
        return ZonedDateTime.parse(date, dateTimeFormatter);
    }

    public ZonedDateTime parseLocalDate(@NotNull final String date) {
        final DateTimeFormatterBuilder dateTimeFormatterBuilder = new DateTimeFormatterBuilder()
                .append(DateTimeFormatter.ofPattern(
                        "[yyyy-MM-dd'T'HH:mm:ss.SSSSSS]"
                                + "[yyyy-MM-dd'T'HH:mm:ss.SSSS]"
                                + "[yyyy-MM-dd'T'HH:mm:ss]"
                                + "[yyyy-MM-dd'T'HH:mm]"
                ));
        final DateTimeFormatter dateTimeFormatter = dateTimeFormatterBuilder.toFormatter();

        return LocalDateTime.parse(date, dateTimeFormatter).atZone(ZoneId.of("UTC"));
    }

}
