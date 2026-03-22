package secondbrain.domain.date;

import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * A service for parsing dates in the format yyyy-MM-dd.
 * The resulting ZonedDateTime is set to midnight in the system default time zone.
 */
@ApplicationScoped
@Identifier("yyyyMmDd")
public class DateParserYyyyMmDd implements DateParser {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Override
    public ZonedDateTime parseDate(final String date) {
        return LocalDate.parse(date, FORMATTER).atStartOfDay(ZoneId.systemDefault());
    }
}

