package secondbrain.domain.persist;

import io.vavr.API;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.faulttolerance.Retry;
import secondbrain.domain.exceptionhandling.ExceptionHandler;
import secondbrain.domain.exceptions.ExternalFailure;
import secondbrain.domain.json.JsonDeserializer;

import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.logging.Logger;

import static com.google.common.base.Predicates.instanceOf;

@ApplicationScoped
public class H2LocalStorage implements LocalStorage {

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
    @ConfigProperty(name = "sb.cache.path")
    private Optional<String> path;

    @Inject
    private JsonDeserializer jsonDeserializer;

    @Inject
    private ExceptionHandler exceptionHandler;

    @Inject
    private Logger logger;

    private String getDatabasePath() {
        return path
                .map(p -> Paths.get(p, "localstoragev2").toAbsolutePath().toString())
                .orElse("./localstoragev2");
    }

    private String getConnectionString() {
        return "jdbc:h2:file:" + getDatabasePath() + ";" + """
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
    }

    private boolean isDisabled() {
        return disable != null && disable.isPresent() && Boolean.parseBoolean(disable.get());
    }

    private boolean isReadOnly() {
        return readOnly != null && readOnly.isPresent() && Boolean.parseBoolean(readOnly.get());
    }

    private boolean isWriteOnly() {
        return writeOnly != null && writeOnly.isPresent() && Boolean.parseBoolean(writeOnly.get());
    }

    private boolean deleteExpired(final Connection connection) {
        if (isDisabled()) {
            return false;
        }

        return Try
                .of(() -> connection.prepareStatement("""
                        DELETE FROM local_storage
                        WHERE timestamp IS NOT NULL
                        AND timestamp < CURRENT_TIMESTAMP""".stripIndent()))
                .mapTry(PreparedStatement::executeUpdate)
                .mapFailure(
                        API.Case(API.$(), ex -> new ExternalFailure("Failed to delete old records", ex))
                )
                .isSuccess();
    }

    @Retry
    @Override
    public String getString(final String tool, final String source, final String promptHash) {
        if (isDisabled() || isWriteOnly()) {
            return null;
        }

        return Try.withResources(() -> DriverManager.getConnection(getConnectionString()))
                .of(connection -> Try
                        .of(() -> getString(connection, tool, source, promptHash))
                        .andFinallyTry(() -> deleteExpired(connection))
                        .andFinallyTry(() -> connection.createStatement().execute("SHUTDOWN"))
                        .getOrNull())
                .mapFailure(
                        API.Case(API.$(instanceOf(ExternalFailure.class)), ex -> ex),
                        API.Case(API.$(), ex -> new ExternalFailure("Failed to delete old records", ex))
                )
                .getOrNull();
    }


    private String getString(final Connection connection, final String tool, final String source, final String promptHash) {
        if (isDisabled() || isWriteOnly()) {
            return null;
        }

        return Try
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
                .mapFailure(
                        API.Case(API.$(), ex -> new ExternalFailure("Failed to get record", ex))
                )
                .onSuccess(value -> deleteExpired(connection))
                .getOrNull();
    }

    @Override
    public String getOrPutString(final String tool, final String source, final String promptHash, final int ttlSeconds, final GenerateValue<String> generateValue) {
        if (isDisabled()) {
            return generateValue.generate();
        }

        return Try.withResources(() -> DriverManager.getConnection(getConnectionString()))
                .of(connection -> Try
                        .of(() -> getString(connection, tool, source, promptHash))
                        // a cache miss means the string is empty, so we throw an exception
                        .map(result -> {
                            if (StringUtils.isBlank(result)) {
                                throw new RuntimeException("Cache miss");
                            }
                            return result;
                        })
                        // recover from a cache miss by generating the value and saving it
                        .recover(result -> {
                            final String value = generateValue.generate();
                            putString(connection, tool, source, promptHash, ttlSeconds, value);
                            return value;
                        })
                        .andFinallyTry(() -> connection.createStatement().execute("SHUTDOWN"))
                        .getOrNull())
                /*
                    Exceptions are swallowed here because caching is just a best effort. But
                    we still need to know if something went wrong.
                 */
                .onFailure(ex -> logger.info(exceptionHandler.getExceptionMessage(ex)))
                // If we can't open the database for some reason, just generate the value
                .recover(ex -> generateValue.generate())
                .getOrNull();
    }

    @Override
    public String getOrPutString(final String tool, final String source, final String promptHash, final GenerateValue<String> generateValue) {
        return getOrPutString(tool, source, promptHash, 0, generateValue);
    }

    @Override
    public <T> T getOrPutObject(final String tool, final String source, final String promptHash, final int ttlSeconds, final Class<T> clazz, final GenerateValue<T> generateValue) {
        return Try.withResources(() -> DriverManager.getConnection(getConnectionString()))
                .of(connection ->
                        Try.of(() -> getString(connection, tool, source, promptHash))
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
                                                connection,
                                                tool,
                                                source,
                                                promptHash,
                                                ttlSeconds,
                                                jsonDeserializer.serialize(r))))
                                .onFailure(ex -> logger.info(exceptionHandler.getExceptionMessage(ex)))
                                .andFinallyTry(() -> connection.createStatement().execute("SHUTDOWN"))
                                .get())
                /*
                    Exceptions are swallowed here because caching is just a best effort. But
                    we still need to know if something went wrong.
                 */
                .onFailure(ex -> logger.info(exceptionHandler.getExceptionMessage(ex)))
                // If we can't open the database for some reason, just generate the value
                .recover(ex -> generateValue.generate())
                .getOrNull();
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

        Try.withResources(() -> DriverManager.getConnection(getConnectionString()))
                .of(connection -> Try
                        .run(() -> putString(connection, tool, source, promptHash, ttlSeconds, response))
                        .andFinallyTry(() -> connection.createStatement().execute("SHUTDOWN"))
                        .mapFailure(
                                API.Case(API.$(instanceOf(ExternalFailure.class)), ex -> ex),
                                API.Case(API.$(), ex -> new ExternalFailure("Failed to add records", ex))
                        ))
                .get();
    }

    private void putString(final Connection connection, final String tool, final String source, final String promptHash, final int ttlSeconds, final String response) {
        if (isDisabled() || isReadOnly()) {
            return;
        }

        Try
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
                .mapFailure(
                        API.Case(API.$(), ex -> new ExternalFailure("Failed to cerate record", ex))
                )
                .get();
    }

    @Override
    public void putString(final String tool, final String source, final String promptHash, final String value) {
        putString(tool, source, promptHash, 0, value);
    }
}
