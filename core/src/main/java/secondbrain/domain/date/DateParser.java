package secondbrain.domain.date;

import jakarta.validation.constraints.NotNull;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;

public class DateParser {
    public static ZonedDateTime parseDate(@NotNull final String date) {
        final DateTimeFormatterBuilder dateTimeFormatterBuilder = new DateTimeFormatterBuilder()
                .append(DateTimeFormatter.ofPattern("[MM/dd/yyyy]"
                        + "[dd-MM-yyyy]"
                        + "[yyyy-MM-dd]"
                        + "[" + DateTimeFormatter.ISO_LOCAL_DATE_TIME + "]"));
        final DateTimeFormatter dateTimeFormatter = dateTimeFormatterBuilder.toFormatter();
        return ZonedDateTime.parse(date, dateTimeFormatter);
    }
}
