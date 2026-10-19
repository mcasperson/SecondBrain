package secondbrain.infrastructure.snowflake;

import org.jspecify.annotations.Nullable;

import java.time.LocalDate;

/**
 * A single row returned by a {@link SnowflakeConnection} query. This exposes the column accessors used to build the
 * Snowflake records without depending on JDBC types.
 */
public interface SnowflakeRow {
    /**
     * @param column The column label
     * @return The column value as a string, or null if the value is SQL NULL
     */
    @Nullable
    String getString(String column);

    /**
     * @param column The column label
     * @param type   The type to convert the column value to
     * @return The column value, or null if the value is SQL NULL
     */
    @Nullable
    <T> T getObject(String column, Class<T> type);

    /**
     * @param column The column label
     * @return The column value as a date, or null if the value is SQL NULL
     */
    @Nullable
    LocalDate getDate(String column);
}
