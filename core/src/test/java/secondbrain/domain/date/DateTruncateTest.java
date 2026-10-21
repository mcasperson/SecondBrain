package secondbrain.domain.date;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

class DateTruncateTest {
    private static final ZoneOffset OFFSET = ZoneOffset.ofHours(2);
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private static OffsetDateTime offset(final int year, final int month, final int day, final int hour, final int minute) {
        return OffsetDateTime.of(year, month, day, hour, minute, 30, 123_000_000, OFFSET);
    }

    private static OffsetDateTime offsetMidnight(final int year, final int month, final int day) {
        return OffsetDateTime.of(year, month, day, 0, 0, 0, 0, OFFSET);
    }

    private static ZonedDateTime zoned(final int year, final int month, final int day, final int hour, final int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 30, 123_000_000, BERLIN);
    }

    private static ZonedDateTime zonedMidnight(final int year, final int month, final int day) {
        return ZonedDateTime.of(year, month, day, 0, 0, 0, 0, BERLIN);
    }

    @Test
    void offsetWeeksTruncatesMidweekToPreviousMondayAtMidnight() {
        // 2024-05-15 is a Wednesday
        assertEquals(offsetMidnight(2024, 5, 13), DateTruncate.truncate(offset(2024, 5, 15, 13, 45), ChronoUnit.WEEKS));
    }

    @Test
    void offsetWeeksKeepsMondayAndDropsTheTime() {
        assertEquals(offsetMidnight(2024, 5, 13), DateTruncate.truncate(offset(2024, 5, 13, 10, 0), ChronoUnit.WEEKS));
    }

    @Test
    void offsetWeeksTruncatesSundayToTheMondaySixDaysEarlier() {
        assertEquals(offsetMidnight(2024, 5, 13), DateTruncate.truncate(offset(2024, 5, 19, 23, 59), ChronoUnit.WEEKS));
    }

    @Test
    void offsetWeeksCrossesTheMonthBoundaryInALeapYear() {
        // 2024-03-02 is a Saturday, and February 2024 has 29 days
        assertEquals(offsetMidnight(2024, 2, 26), DateTruncate.truncate(offset(2024, 3, 2, 8, 15), ChronoUnit.WEEKS));
    }

    @Test
    void offsetMonthsTruncatesToFirstDayOfMonthAtMidnight() {
        assertEquals(offsetMidnight(2024, 5, 1), DateTruncate.truncate(offset(2024, 5, 15, 13, 45), ChronoUnit.MONTHS));
    }

    @Test
    void offsetMonthsKeepsFirstDayAndDropsTheTime() {
        assertEquals(offsetMidnight(2024, 5, 1), DateTruncate.truncate(offset(2024, 5, 1, 0, 1), ChronoUnit.MONTHS));
    }

    @Test
    void offsetMonthsTruncatesLastDayOfMonth() {
        assertEquals(offsetMidnight(2024, 2, 1), DateTruncate.truncate(offset(2024, 2, 29, 23, 59), ChronoUnit.MONTHS));
    }

    @Test
    void offsetYearsTruncatesToFirstDayOfYearAtMidnight() {
        assertEquals(offsetMidnight(2024, 1, 1), DateTruncate.truncate(offset(2024, 5, 15, 13, 45), ChronoUnit.YEARS));
    }

    @Test
    void offsetYearsTruncatesLastDayOfLeapYear() {
        assertEquals(offsetMidnight(2024, 1, 1), DateTruncate.truncate(offset(2024, 12, 31, 23, 59), ChronoUnit.YEARS));
    }

    @Test
    void offsetYearsKeepsFirstDayAndDropsTheTime() {
        assertEquals(offsetMidnight(2025, 1, 1), DateTruncate.truncate(offset(2025, 1, 1, 12, 0), ChronoUnit.YEARS));
    }

    @Test
    void offsetTruncationPreservesTheOffset() {
        final OffsetDateTime result = DateTruncate.truncate(offset(2024, 5, 15, 13, 45), ChronoUnit.MONTHS);

        assertEquals(OFFSET, result.getOffset());
    }

    @ParameterizedTest
    @EnumSource(value = ChronoUnit.class, names = {"WEEKS", "MONTHS", "YEARS"}, mode = EnumSource.Mode.EXCLUDE)
    void offsetUnsupportedUnitThrows(final ChronoUnit unit) {
        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> DateTruncate.truncate(offset(2024, 5, 15, 13, 45), unit));

        assertEquals("Unsupported ChronoUnit: " + unit + ". Only WEEKS, MONTHS and YEARS are supported.", ex.getMessage());
    }

    @Test
    void zonedWeeksTruncatesMidweekToPreviousMondayAtMidnight() {
        assertEquals(zonedMidnight(2024, 5, 13), DateTruncate.truncate(zoned(2024, 5, 15, 13, 45), ChronoUnit.WEEKS));
    }

    @Test
    void zonedWeeksKeepsMondayAndDropsTheTime() {
        assertEquals(zonedMidnight(2024, 5, 13), DateTruncate.truncate(zoned(2024, 5, 13, 10, 0), ChronoUnit.WEEKS));
    }

    @Test
    void zonedWeeksTruncatesSundayToTheMondaySixDaysEarlier() {
        assertEquals(zonedMidnight(2024, 5, 13), DateTruncate.truncate(zoned(2024, 5, 19, 23, 59), ChronoUnit.WEEKS));
    }

    @Test
    void zonedWeeksAcrossDaylightSavingChangeUsesTheEarlierOffset() {
        // Berlin moves from +01:00 to +02:00 on Sunday 2024-03-31
        final ZonedDateTime result = DateTruncate.truncate(zoned(2024, 3, 31, 12, 0), ChronoUnit.WEEKS);

        assertEquals(zonedMidnight(2024, 3, 25), result);
        assertEquals(ZoneOffset.ofHours(1), result.getOffset());
        assertEquals(BERLIN, result.getZone());
    }

    @Test
    void zonedMonthsTruncatesToFirstDayOfMonthAtMidnight() {
        final ZonedDateTime result = DateTruncate.truncate(zoned(2024, 4, 15, 13, 45), ChronoUnit.MONTHS);

        assertEquals(zonedMidnight(2024, 4, 1), result);
        assertEquals(ZoneOffset.ofHours(2), result.getOffset());
    }

    @Test
    void zonedMonthsKeepsFirstDayAndDropsTheTime() {
        assertEquals(zonedMidnight(2024, 5, 1), DateTruncate.truncate(zoned(2024, 5, 1, 0, 1), ChronoUnit.MONTHS));
    }

    @Test
    void zonedYearsTruncatesToFirstDayOfYearAtMidnight() {
        final ZonedDateTime result = DateTruncate.truncate(zoned(2024, 7, 15, 13, 45), ChronoUnit.YEARS);

        assertEquals(zonedMidnight(2024, 1, 1), result);
        assertEquals(ZoneOffset.ofHours(1), result.getOffset());
    }

    @Test
    void zonedYearsTruncatesLastDayOfLeapYear() {
        assertEquals(zonedMidnight(2024, 1, 1), DateTruncate.truncate(zoned(2024, 12, 31, 23, 59), ChronoUnit.YEARS));
    }

    @ParameterizedTest
    @EnumSource(value = ChronoUnit.class, names = {"WEEKS", "MONTHS", "YEARS"}, mode = EnumSource.Mode.EXCLUDE)
    void zonedUnsupportedUnitThrows(final ChronoUnit unit) {
        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> DateTruncate.truncate(zoned(2024, 5, 15, 13, 45), unit));

        assertEquals("Unsupported ChronoUnit: " + unit + ". Only WEEKS, MONTHS and YEARS are supported.", ex.getMessage());
    }
}
