package secondbrain.domain.persist;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.faulttolerance.Retry;
import secondbrain.domain.json.JsonDeserializer;

import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;

@ApplicationScoped
public class H2LocalStorage implements LocalStorage {

    private static final String DATABASE = """
            jdbc:h2:file:./localstoragev2;
            INIT=CREATE SCHEMA IF NOT EXISTS SECONDBRAIN\\;
            SET SCHEMA SECONDBRAIN\\;
            CREATE TABLE IF NOT EXISTS local_storage
            (tool VARCHAR(100) NOT NULL,
            source VARCHAR(1024) NOT NULL,
            prompt_hash VARCHAR(1024) NOT NULL,
            response CLOB NOT NULL,
            timestamp TIMESTAMP DEFAULT NULL)\\;
            CREATE INDEX IF NOT EXISTS idx_timestamp ON local_storage(timestamp)\\;
            CREATE INDEX IF NOT EXISTS idx_tool ON local_storage(tool)\\;
            CREATE INDEX IF NOT EXISTS idx_source ON local_storage(source)\\;
            CREATE INDEX IF NOT EXISTS idx_prompt_hash ON local_storage(prompt_hash);""".stripIndent().replaceAll("\n", "");

    @Inject
    @ConfigProperty(name = "sb.cache.disable")
    private Optional<String> disable;

    @Inject
    @ConfigProperty(name = "sb.cache.readonly")
    private Optional<String> readOnly;

    @Inject
    @ConfigProperty(name = "sb.cache.writeonly")
    private Optional<String> writeOnly;

    @Inject
    private JsonDeserializer jsonDeserializer;

    private boolean isDisabled() {
        return disable != null && disable.isPresent() && Boolean.parseBoolean(disable.get());
    }

    private boolean isReadOnly() {
        return readOnly != null && readOnly.isPresent() && Boolean.parseBoolean(readOnly.get());
    }

    private boolean isWriteOnly() {
        return writeOnly != null && writeOnly.isPresent() && Boolean.parseBoolean(writeOnly.get());
    }

    private boolean deleteExpired() {
        if (isDisabled()) {
            return false;
        }

        return Try.withResources(() -> DriverManager.getConnection(DATABASE))
                .of(connection -> Try
                        .of(() -> connection.prepareStatement("""
                                DELETE FROM local_storage
                                WHERE timestamp IS NOT NULL
                                AND timestamp < CURRENT_TIMESTAMP""".stripIndent()))
                        .mapTry(PreparedStatement::executeUpdate)
                        .onFailure(Throwable::printStackTrace)
                        .isSuccess())
                .onFailure(Throwable::printStackTrace)
                .getOrElse(false);
    }

    @Retry
    @Override
    public String getString(final String tool, final String source, final String promptHash) {
        if (isDisabled() || isWriteOnly()) {
            return null;
        }

        deleteExpired();

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
    public String getOrPutString(final String tool, final String source, final String promptHash, final int ttlSeconds, final GenerateValue<String> generateValue) {
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
    public String getOrPutString(String tool, String source, String promptHash, GenerateValue<String> generateValue) {
        return getOrPutString(tool, source, promptHash, 0, generateValue);
    }

    @Override
    public <T> T getOrPutObject(final String tool, final String source, final String promptHash, final int ttlSeconds, final Class<T> clazz, final GenerateValue<T> generateValue) {
        return Try.of(() -> getString(tool, source, promptHash))
                // a cache miss means the string is empty, so we throw an exception
                .map(result -> {
                    if (StringUtils.isBlank(result)) {
                        throw new RuntimeException("Cache miss");
                    }
                    return result;
                })
                // a cache hit means we deserialize the result
                .mapTry(r -> jsonDeserializer.deserialize(r, clazz))
                // a cache miss means we call the API and then save the result in the cache
                .recoverWith(ex -> Try.of(generateValue::generate)
                        .onSuccess(r -> putString(
                                tool,
                                source,
                                promptHash,
                                ttlSeconds,
                                jsonDeserializer.serialize(r))))
                .get();
    }

    @Override
    public <T> T getOrPutObject(final String tool, final String source, final String promptHash, final Class<T> clazz, final GenerateValue<T> generateValue) {
        return getOrPutObject(tool, source, promptHash, 0, clazz, generateValue);
    }

    @Retry
    @Override
    public void putString(final String tool, final String source, final String promptHash, final int ttlSeconds, final String response) {
        if (isDisabled() || isReadOnly()) {
            return;
        }

        Try.withResources(() -> DriverManager.getConnection(DATABASE))
                .of(connection -> Try
                        .of(() -> connection.prepareStatement("""
                                INSERT INTO local_storage (tool, source, prompt_hash, response, timestamp)
                                VALUES (?, ?, ?, ?, ?)""".stripIndent()))
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
