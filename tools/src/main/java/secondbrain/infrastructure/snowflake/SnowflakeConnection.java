package secondbrain.infrastructure.snowflake;

import java.util.List;
import java.util.function.Function;

/**
 * Exposes the Snowflake JDBC connection operations used by {@link SnowflakeClientLive} behind an interface.
 * The JDBC connection is built from the driver manager and has no project interface, so this adapter allows it
 * to be swapped for a test instance.
 */
public interface SnowflakeConnection {
    /**
     * Runs a query that has a single string parameter.
     *
     * @param sql       The SQL, with one "?" placeholder
     * @param parameter The value bound to the placeholder
     * @param rowMapper Converts each returned row
     * @return The converted rows, in the order they were returned
     */
    <T> List<T> query(String sql, String parameter, Function<SnowflakeRow, T> rowMapper);

    /**
     * Releases the connection.
     */
    void close();
}
