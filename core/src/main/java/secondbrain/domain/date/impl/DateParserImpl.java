package secondbrain.domain.date.impl;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import secondbrain.domain.date.DateParser;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;

/**
 * A service for parsing dates. It handles ISO8601 dates with and without time zones.
 */
@ApplicationScoped
public class DateParserImpl implements DateParser {
    @Override
    public ZonedDateTime parseDate(final String date) {
        return Try.of(() -> parseZonedDate(date))
                .recoverWith(error -> Try.of(() -> parseLocalDate(date)))
                .get();
    }

    public ZonedDateTime parseZonedDate(final String date) {
        final DateTimeFormatterBuilder dateTimeFormatterBuilder = new DateTimeFormatterBuilder()
                .append(DateTimeFormatter.ofPattern(
                        "[yyyy-MM-dd'T'HH:mmz]"
                                + "[yyyy-MM-dd'T'HH:mm:ssz]"
                                + "[yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSSz]"
                                + "[yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSz]"
                                + "[yyyy-MM-dd'T'HH:mm:ss.SSSSSSSz]"
                                + "[yyyy-MM-dd'T'HH:mm:ss.SSSSSSz]"
                                + "[yyyy-MM-dd'T'HH:mm:ss.SSSSz]"
                                + "[yyyy-MM-dd'T'HH:mm:ss.SSSz]"
                                + "[yyyy-MM-dd'T'HH:mm:ss.SSz]"
                                + "[yyyy-MM-dd'T'HH:mm:ss.Sz]"
                ));
        final DateTimeFormatter dateTimeFormatter = dateTimeFormatterBuilder.toFormatter();
        return ZonedDateTime.parse(date, dateTimeFormatter);
    }

    public ZonedDateTime parseLocalDate(final String date) {
        final DateTimeFormatterBuilder dateTimeFormatterBuilder = new DateTimeFormatterBuilder()
                .append(DateTimeFormatter.ofPattern(
                        "[yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSS]"
                                + "[yyyy-MM-dd'T'HH:mm:ss.SSSSSSSS]"
                                + "[yyyy-MM-dd'T'HH:mm:ss.SSSSSSS]"
                                + "[yyyy-MM-dd'T'HH:mm:ss.SSSSSS]"
                                + "[yyyy-MM-dd'T'HH:mm:ss.SSSSS]"
                                + "[yyyy-MM-dd'T'HH:mm:ss.SSSS]"
                                + "[yyyy-MM-dd'T'HH:mm:ss.SSS]"
                                + "[yyyy-MM-dd'T'HH:mm:ss.SS]"
                                + "[yyyy-MM-dd'T'HH:mm:ss.S]"
                                + "[yyyy-MM-dd'T'HH:mm:ss]"
                                + "[yyyy-MM-dd'T'HH:mm]"
                ));
        final DateTimeFormatter dateTimeFormatter = dateTimeFormatterBuilder.toFormatter();

        return LocalDateTime.parse(date, dateTimeFormatter).atZone(ZoneId.of("UTC"));
    }

}
