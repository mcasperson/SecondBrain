package secondbrain.domain.persist;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.faulttolerance.Retry;

import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;

@ApplicationScoped
public class H2LocalStorage implements LocalStorage {

    private static final String DATABASE = "jdbc:h2:file:./localstorage;"
            + "INIT=CREATE SCHEMA IF NOT EXISTS SECONDBRAIN\\;"
            + "SET SCHEMA SECONDBRAIN\\;"
            + """
            CREATE TABLE IF NOT EXISTS local_storage
             (tool VARCHAR(100) NOT NULL,
              source VARCHAR(1024) NOT NULL,
              prompt_hash VARCHAR(1024) NOT NULL,
              response CLOB NOT NULL,
              timestamp TIMESTAMP DEFAULT NULL);""".stripIndent();

    @Inject
    @ConfigProperty(name = "sb.cache.disable")
    private Optional<String> disable;

    private boolean isDisabled() {
        return disable.isPresent() && Boolean.parseBoolean(disable.get());
    }

    @Retry
    @Override
    public String getString(final String tool, final String source, final String promptHash) {
        if (isDisabled()) {
            return null;
        }

        return Try.withResources(() -> DriverManager.getConnection(DATABASE))
                .of(connection -> Try
                        .of(() -> connection.prepareStatement("""
                                SELECT response FROM local_storage
                                                WHERE tool = ?
                                                AND source = ?
                                                AND prompt_hash = ?
                                                AND (timestamp IS NULL OR timestamp > CURRENT_TIMESTAMP)""".stripIndent()))
                        .mapTry(preparedStatement -> {
                            preparedStatement.setString(1, tool);
                            preparedStatement.setString(2, source);
                            preparedStatement.setString(3, promptHash);
                            return preparedStatement;
                        })
                        .mapTry(PreparedStatement::executeQuery)
                        .mapTry(resultSet -> {
                            if (resultSet.next()) {
                                return resultSet.getString(1);
                            }
                            return null;
                        })
                        .onFailure(Throwable::printStackTrace)
                        .getOrNull())
                .onFailure(Throwable::printStackTrace)
                .getOrNull();
    }

    @Override
    public String getOrPutString(final String tool, final String source, final String promptHash, final int ttlSeconds, final GenerateValue generateValue) {
        if (isDisabled()) {
            return generateValue.generate();
        }

        final String cache = getString(tool, source, promptHash);
        if (StringUtils.isNotBlank(cache) && !isDisabled()) {
            return cache;
        }

        final String newValue = generateValue.generate();
        putString(tool, source, promptHash, ttlSeconds, newValue);
        return newValue;
    }

    @Override
    public String getOrPutString(String tool, String source, String promptHash, GenerateValue generateValue) {
        return getOrPutString(tool, source, promptHash, 0, generateValue);
    }

    @Retry
    @Override
    public void putString(final String tool, final String source, final String promptHash, final int ttlSeconds, final String response) {
        if (isDisabled()) {
            return;
        }

        Try.withResources(() -> DriverManager.getConnection(DATABASE))
                .of(connection -> Try
                        .of(() -> connection.prepareStatement("INSERT INTO local_storage (tool, source, prompt_hash, response) VALUES (?, ?, ?, ?, ?)"))
                        .mapTry(preparedStatement -> {
                            preparedStatement.setString(1, tool);
                            preparedStatement.setString(2, source);
                            preparedStatement.setString(3, promptHash);
                            preparedStatement.setString(4, response);
                            preparedStatement.setTimestamp(5, ttlSeconds == 0
                                    ? null
                                    : Timestamp.from(ZonedDateTime
                                    .now(ZoneOffset.UTC)
                                    .plusSeconds(ttlSeconds)
                                    .toInstant()));
                            preparedStatement.executeUpdate();
                            return preparedStatement;
                        })
                        .onFailure(Throwable::printStackTrace))
                .onFailure(Throwable::printStackTrace);
    }

    @Override
    public void putString(String tool, String source, String promptHash, String value) {
        putString(tool, source, promptHash, 0, value);
    }
}
