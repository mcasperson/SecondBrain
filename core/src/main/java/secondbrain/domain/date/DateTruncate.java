package secondbrain.domain.date;

import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;

public final class DateTruncate {

    /**
     * Truncates an {@link OffsetDateTime} to the start of the given {@link ChronoUnit}.
     * Supports {@link ChronoUnit#WEEKS}, {@link ChronoUnit#MONTHS} and {@link ChronoUnit#YEARS}.
     *
     * @param dateTime  the date-time to truncate
     * @param chronoUnit the unit to truncate to ({@code WEEKS}, {@code MONTHS} or {@code YEARS})
     * @return the truncated {@link OffsetDateTime}
     * @throws IllegalArgumentException if an unsupported {@link ChronoUnit} is provided
     */
    public static OffsetDateTime truncate(final OffsetDateTime dateTime, final ChronoUnit chronoUnit) {
        return switch (chronoUnit) {
            case WEEKS -> dateTime
                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .truncatedTo(ChronoUnit.DAYS);
            case MONTHS -> dateTime
                    .withDayOfMonth(1)
                    .truncatedTo(ChronoUnit.DAYS);
            case YEARS -> dateTime
                    .withDayOfYear(1)
                    .truncatedTo(ChronoUnit.DAYS);
            default -> throw new IllegalArgumentException(
                    "Unsupported ChronoUnit: " + chronoUnit + ". Only WEEKS, MONTHS and YEARS are supported.");
        };
    }

    /**
     * Truncates a {@link ZonedDateTime} to the start of the given {@link ChronoUnit}.
     * Supports {@link ChronoUnit#WEEKS}, {@link ChronoUnit#MONTHS} and {@link ChronoUnit#YEARS}.
     *
     * @param dateTime   the date-time to truncate
     * @param chronoUnit the unit to truncate to ({@code WEEKS}, {@code MONTHS} or {@code YEARS})
     * @return the truncated {@link ZonedDateTime}
     * @throws IllegalArgumentException if an unsupported {@link ChronoUnit} is provided
     */
    public static ZonedDateTime truncate(final ZonedDateTime dateTime, final ChronoUnit chronoUnit) {
        return switch (chronoUnit) {
            case WEEKS -> dateTime
                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .truncatedTo(ChronoUnit.DAYS);
            case MONTHS -> dateTime
                    .withDayOfMonth(1)
                    .truncatedTo(ChronoUnit.DAYS);
            case YEARS -> dateTime
                    .withDayOfYear(1)
                    .truncatedTo(ChronoUnit.DAYS);
            default -> throw new IllegalArgumentException(
                    "Unsupported ChronoUnit: " + chronoUnit + ". Only WEEKS, MONTHS and YEARS are supported.");
        };
    }
}
