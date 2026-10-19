package secondbrain.domain.test;

import org.jspecify.annotations.Nullable;
import secondbrain.infrastructure.snowflake.SnowflakeConnection;
import secondbrain.infrastructure.snowflake.SnowflakeRow;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * An in-memory SnowflakeConnection. Each query is recorded, and returns the primed rows (column label to value)
 * passed through the caller's row mapper. Expose it with a @Produces method, and a @Disposes method that calls close().
 */
public final class FakeSnowflakeConnection implements SnowflakeConnection {
    public record Query(String sql, String parameter) {
    }

    public final List<Query> queries = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> rows = new ArrayList<>();
    private final AtomicInteger closes = new AtomicInteger();
    @Nullable
    private volatile RuntimeException failure;

    /**
     * Return this row, after any previously added rows, from every query.
     */
    public FakeSnowflakeConnection addRow(final Map<String, Object> row) {
        rows.add(Map.copyOf(row));
        return this;
    }

    /**
     * Make every subsequent query throw the exception.
     */
    public FakeSnowflakeConnection failWith(final RuntimeException failure) {
        this.failure = failure;
        return this;
    }

    public int closes() {
        return closes.get();
    }

    @Override
    public <T> List<T> query(final String sql, final String parameter, final Function<SnowflakeRow, T> rowMapper) {
        queries.add(new Query(sql, parameter));
        final RuntimeException ex = failure;
        if (ex != null) {
            throw ex;
        }
        return rows.stream().map(row -> rowMapper.apply(new MapRow(row))).toList();
    }

    @Override
    public void close() {
        closes.incrementAndGet();
    }

    private record MapRow(Map<String, Object> values) implements SnowflakeRow {
        @Override
        @Nullable
        public String getString(final String column) {
            return getObject(column, String.class);
        }

        @Override
        @Nullable
        public <T> T getObject(final String column, final Class<T> type) {
            return type.cast(values.get(column));
        }

        @Override
        @Nullable
        public LocalDate getDate(final String column) {
            return getObject(column, LocalDate.class);
        }
    }
}
