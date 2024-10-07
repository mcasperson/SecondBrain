package secondbrain.domain.date;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;

public class DateParser {
    public static LocalDate parseDate(@NotNull final String date) {
        final DateTimeFormatterBuilder dateTimeFormatterBuilder = new DateTimeFormatterBuilder()
                .append(DateTimeFormatter.ofPattern("[MM/dd/yyyy]"
                        + "[dd-MM-yyyy]"
                        + "[yyyy-MM-dd]"
                        + "[" + DateTimeFormatter.ISO_DATE_TIME + "]"));
        final DateTimeFormatter dateTimeFormatter = dateTimeFormatterBuilder.toFormatter();
        return LocalDate.parse(date, dateTimeFormatter);
    }
}
