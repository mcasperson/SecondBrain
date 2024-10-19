package secondbrain.domain.date;

import java.time.ZonedDateTime;

/**
 * Defines a service for parsing dates
 */
public interface DateParser {
    /**
     * Parse a date into a ZonedDateTime
     * @param date The date string
     * @return The parsed date
     */
    ZonedDateTime parseDate(String date);
}
