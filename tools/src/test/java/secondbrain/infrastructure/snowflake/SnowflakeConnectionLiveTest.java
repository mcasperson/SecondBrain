package secondbrain.infrastructure.snowflake;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SnowflakeConnectionLive}. The JDBC objects are passed to the adapter's constructor as Mockito
 * mocks, so no Snowflake account is needed.
 */
class SnowflakeConnectionLiveTest {
	private static final String SQL = "SELECT * FROM T WHERE ID = ?";

	private final Connection connection = mock(Connection.class);
	private final PreparedStatement statement = mock(PreparedStatement.class);
	private final ResultSet resultSet = mock(ResultSet.class);
	private final SnowflakeConnectionLive live = new SnowflakeConnectionLive(connection);

	private void stubQuery() throws SQLException {
		when(connection.prepareStatement(SQL)).thenReturn(statement);
		when(statement.executeQuery()).thenReturn(resultSet);
	}

	@Test
	void queryBindsTheParameterAndMapsEveryRowInOrder() throws SQLException {
		stubQuery();
		when(resultSet.next()).thenReturn(true, true, false);
		when(resultSet.getString("NAME")).thenReturn("first", "second");

		final List<String> results = live.query(SQL, "account-1", row -> row.getString("NAME"));

		assertEquals(List.of("first", "second"), results);
		verify(statement).setString(1, "account-1");
	}

	@Test
	void queryClosesTheResultSetAndStatementButNotTheConnection() throws SQLException {
		stubQuery();
		when(resultSet.next()).thenReturn(true, false);
		when(resultSet.getString("NAME")).thenReturn("first");

		live.query(SQL, "account-1", row -> row.getString("NAME"));

		// The result set must be closed before the statement that created it
		final var order = inOrder(resultSet, statement);
		order.verify(resultSet).close();
		order.verify(statement).close();
		verify(connection, never()).close();
	}

	@Test
	void queryClosesTheResultSetAndStatementWhenARowFails() throws SQLException {
		stubQuery();
		when(resultSet.next()).thenReturn(true);
		final SQLException failure = new SQLException("bad column");
		when(resultSet.getString("MISSING")).thenThrow(failure);

		assertSame(failure, assertThrows(SQLException.class, () -> live.query(SQL, "p", row -> row.getString("MISSING"))));

		verify(resultSet).close();
		verify(statement).close();
	}

	@Test
	void queryClosesTheStatementWhenTheQueryFails() throws SQLException {
		when(connection.prepareStatement(SQL)).thenReturn(statement);
		when(statement.executeQuery()).thenThrow(new SQLException("timeout"));

		assertThrows(SQLException.class, () -> live.query(SQL, "p", row -> "row"));

		verify(statement).close();
	}

	@Test
	void queryWithNoRowsReturnsAnEmptyList() throws SQLException {
		stubQuery();
		when(resultSet.next()).thenReturn(false);
		final List<String> mapped = new ArrayList<>();

		final List<String> results = live.query(SQL, "account-1", row -> {
			mapped.add("called");
			return "row";
		});

		assertEquals(List.of(), results);
		assertEquals(List.of(), mapped);
	}

	@Test
	void rowsReadObjectsByColumnAndType() throws SQLException {
		stubQuery();
		when(resultSet.next()).thenReturn(true, false);
		when(resultSet.getObject("COUNT", Integer.class)).thenReturn(42);

		assertEquals(List.of(42), live.query(SQL, "p", row -> row.getObject("COUNT", Integer.class)));
	}

	@Test
	void rowsConvertDatesToLocalDates() throws SQLException {
		stubQuery();
		when(resultSet.next()).thenReturn(true, false);
		when(resultSet.getDate("START")).thenReturn(Date.valueOf(LocalDate.of(2025, 3, 31)));

		assertEquals(List.of(LocalDate.of(2025, 3, 31)), live.query(SQL, "p", row -> row.getDate("START")));
	}

	@Test
	void rowsReturnNullForSqlNullDates() throws SQLException {
		stubQuery();
		when(resultSet.next()).thenReturn(true, false);
		when(resultSet.getDate("START")).thenReturn(null);
		final List<LocalDate> dates = new ArrayList<>();

		live.query(SQL, "p", row -> {
			dates.add(row.getDate("START"));
			return "row";
		});

		assertEquals(1, dates.size());
		assertNull(dates.getFirst());
	}

	@Test
	void columnReadFailuresArePropagatedUnchanged() throws SQLException {
		stubQuery();
		when(resultSet.next()).thenReturn(true);
		final SQLException failure = new SQLException("bad column");
		when(resultSet.getString("MISSING")).thenThrow(failure);

		final SQLException thrown = assertThrows(SQLException.class, () -> live.query(SQL, "p", row -> row.getString("MISSING")));

		assertSame(failure, thrown);
	}

	@Test
	void objectAndDateReadFailuresArePropagatedUnchanged() throws SQLException {
		stubQuery();
		when(resultSet.next()).thenReturn(true);
		final SQLException objectFailure = new SQLException("bad object");
		final SQLException dateFailure = new SQLException("bad date");
		when(resultSet.getObject("COUNT", Integer.class)).thenThrow(objectFailure);
		when(resultSet.getDate("START")).thenThrow(dateFailure);

		assertSame(objectFailure, assertThrows(SQLException.class, () -> live.query(SQL, "p", row -> row.getObject("COUNT", Integer.class))));
		assertSame(dateFailure, assertThrows(SQLException.class, () -> live.query(SQL, "p", row -> row.getDate("START"))));
	}

	@Test
	void statementFailuresArePropagatedUnchanged() throws SQLException {
		final SQLException failure = new SQLException("invalid SQL");
		when(connection.prepareStatement(SQL)).thenThrow(failure);

		final SQLException thrown = assertThrows(SQLException.class, () -> live.query(SQL, "p", row -> "row"));

		assertSame(failure, thrown);
	}

	@Test
	void queryFailuresArePropagatedUnchanged() throws SQLException {
		when(connection.prepareStatement(SQL)).thenReturn(statement);
		final SQLException failure = new SQLException("timeout");
		when(statement.executeQuery()).thenThrow(failure);

		final SQLException thrown = assertThrows(SQLException.class, () -> live.query(SQL, "p", row -> "row"));

		assertSame(failure, thrown);
	}

	@Test
	void closeClosesTheConnection() throws SQLException {
		live.close();

		verify(connection).close();
	}

	@Test
	void closeIgnoresFailures() throws SQLException {
		doThrow(new SQLException("already closed")).when(connection).close();

		assertDoesNotThrow(live::close);
		verify(connection).close();
	}
}
