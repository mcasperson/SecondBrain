package secondbrain.domain.date;

import org.jspecify.annotations.Nullable;

import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Defines a service for parsing dates
 */
public interface DateParser {
    /**
     * Parse a date into a ZonedDateTime
     *
     * @param date The date string
     * @return The parsed date
     */
    ZonedDateTime parseDate(String date);

    /**
     * Parse a date into a ZonedDateTime, returning a default value if parsing fails.
     *
     * @param date         The date string
     * @param defaultValue The default value to return if parsing fails
     * @return The parsed date, or the default value if parsing fails
     */
    @Nullable
    default ZonedDateTime parseDateOrDefault(final String date, @Nullable final ZonedDateTime defaultValue) {
        try {
            return parseDate(date);
        } catch (final Exception e) {
            return defaultValue;
        }
    }

    /**
     * Parse a date into a ZonedDateTime, returning a default epoch value if parsing fails.
     *
     * @param date The date string
     * @return The parsed date, or the default value if parsing fails
     */
    default ZonedDateTime parseDateOrDefault(final String date) {
        try {
            return parseDate(date);
        } catch (final Exception e) {
            return ZonedDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneId.systemDefault());
        }
    }
}
