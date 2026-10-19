package secondbrain.infrastructure.snowflake;

import io.vavr.control.Try;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.tryext.TryExtensions;

import java.sql.Connection;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Adapts a JDBC Connection to the SnowflakeConnection interface.
 * Instances are created by {@link SnowflakeConnectionProducer}.
 */
public class SnowflakeConnectionLive implements SnowflakeConnection {
    private final Connection connection;

    public SnowflakeConnectionLive(final Connection connection) {
        this.connection = connection;
    }

    @Override
    public <T> List<T> query(final String sql, final String parameter, final Function<SnowflakeRow, T> rowMapper) {
        // The statement and result set are closed after the rows are read, as the connection is long-lived
        return TryExtensions.withResources(
                        () -> connection.prepareStatement(sql),
                        statement -> {
                            statement.setString(1, parameter);
                            return statement.executeQuery();
                        },
                        rs -> {
                            final SnowflakeRow row = new ResultSetRow(rs);
                            final List<T> results = new ArrayList<>();
                            while (rs.next()) {
                                results.add(rowMapper.apply(row));
                            }
                            return results;
                        })
                .get();
    }

    @Override
    public void close() {
        Try.run(connection::close);
    }

    /**
     * Reads the current row of a result set. SQL exceptions are rethrown unchanged.
     */
    private record ResultSetRow(ResultSet rs) implements SnowflakeRow {
        @Override
        @Nullable
        public String getString(final String column) {
            return Try.of(() -> rs.getString(column)).get();
        }

        @Override
        @Nullable
        public <T> T getObject(final String column, final Class<T> type) {
            return Try.of(() -> rs.getObject(column, type)).get();
        }

        @Override
        @Nullable
        public LocalDate getDate(final String column) {
            final java.sql.Date date = Try.of(() -> rs.getDate(column)).get();
            return date != null ? date.toLocalDate() : null;
        }
    }
}
