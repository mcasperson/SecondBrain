package secondbrain.domain.persist;

import io.vavr.API;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.faulttolerance.Retry;
import secondbrain.domain.exceptionhandling.ExceptionHandler;
import secondbrain.domain.exceptions.LocalStorageFailure;
import secondbrain.domain.json.JsonDeserializer;

import java.nio.file.Paths;
import java.sql.*;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

@ApplicationScoped
public class H2LocalStorage implements LocalStorage {

    private final AtomicInteger totalReads = new AtomicInteger();
    private final AtomicInteger totalCacheHits = new AtomicInteger();
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


    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(getConnectionString());
    }

    private void cleanConnection(final Connection connection) {
        if (connection != null) {
            Try.run(() -> connection.createStatement().execute("SHUTDOWN"))
                    .onFailure(ex -> logger.warning(exceptionHandler.getExceptionMessage(ex)));

            Try.run(connection::close)
                    .onFailure(ex -> logger.warning(exceptionHandler.getExceptionMessage(ex)));
        }
    }

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

    synchronized private boolean deleteExpired(final Connection connection) {
        if (isDisabled() || connection == null) {
            return false;
        }

        final Try<Integer> result = Try
                .of(() -> connection.prepareStatement("""
                        DELETE FROM local_storage
                        WHERE timestamp IS NOT NULL
                        AND timestamp < CURRENT_TIMESTAMP""".stripIndent()))
                .mapTry(PreparedStatement::executeUpdate);

        return result
                .mapFailure(
                        API.Case(API.$(), ex -> new LocalStorageFailure("Failed to delete old records", ex))
                )
                .isSuccess();
    }

    @Retry(delay = 1000)
    @Override
    synchronized public String getString(final String tool, final String source, final String promptHash) {
        if (isDisabled() || isWriteOnly()) {
            return null;
        }

        totalReads.incrementAndGet();

        final Try<String> result = Try
                .withResources(this::getConnection)
                .of(connection -> Try.of(() -> connection.prepareStatement("""
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
                                totalCacheHits.incrementAndGet();
                                return resultSet.getString(1);
                            }
                            return null;
                        })
                        .onSuccess(value -> deleteExpired(connection))
                        .andFinally(() -> cleanConnection(connection)))
                .get();

        return result
                .mapFailure(
                        API.Case(API.$(), ex -> new LocalStorageFailure("Failed to get record", ex))
                )
                .get();
    }

    @Override
    public String getOrPutString(final String tool, final String source, final String promptHash, final int ttlSeconds, final GenerateValue<String> generateValue) {
        if (isDisabled()) {
            return generateValue.generate();
        }

        return Try
                .of(() -> getString(tool, source, promptHash))
                // a cache miss means the string is empty, so we throw an exception
                .filter(StringUtils::isNotBlank)
                // recover from a cache miss by generating the value and saving it
                .recover(result -> {
                    final String value = generateValue.generate();
                    putString(tool, source, promptHash, ttlSeconds, value);
                    return value;
                })
                /*
                    Exceptions are swallowed here because caching is just a best effort. But
                    we still need to know if something went wrong.
                 */
                .onFailure(LocalStorageFailure.class, ex -> logger.warning(exceptionHandler.getExceptionMessage(ex)))
                // If there was an error with the local storage, bypass it and generate the value
                .recover(LocalStorageFailure.class, ex -> generateValue.generate())
                // For all other errors, we return the value or rethrow the exception
                .get();
    }

    @Override
    public String getOrPutString(final String tool, final String source, final String promptHash, final GenerateValue<String> generateValue) {
        return getOrPutString(tool, source, promptHash, 0, generateValue);
    }

    @Override
    public <T> T getOrPutObject(final String tool, final String source, final String promptHash, final int ttlSeconds, final Class<T> clazz, final GenerateValue<T> generateValue) {
        return Try.of(() -> getString(tool, source, promptHash))
                // a cache miss means the string is empty, so we throw an exception
                .filter(StringUtils::isNotBlank)
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
                /*
                    Exceptions are swallowed here because caching is just a best effort. But
                    we still need to know if something went wrong.
                 */
                .onFailure(LocalStorageFailure.class, ex -> logger.warning(exceptionHandler.getExceptionMessage(ex)))
                // If there was an error with the local storage, bypass it and generate the value
                .recover(LocalStorageFailure.class, ex -> generateValue.generate())
                // For all other errors, we return the value or rethrow the exception
                .get();
    }

    @Override
    public <T> T getOrPutObject(final String tool, final String source, final String promptHash, final Class<T> clazz, final GenerateValue<T> generateValue) {
        return getOrPutObject(tool, source, promptHash, 0, clazz, generateValue);
    }

    @Retry(delay = 1000)
    @Override
    synchronized public void putString(final String tool, final String source, final String promptHash, final int ttlSeconds, final String response) {
        if (isDisabled() || isReadOnly()) {
            return;
        }

        final Try<PreparedStatement> result = Try
                .withResources(this::getConnection)
                .of(connection -> Try.of(() -> connection.prepareStatement("""
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
                        .andFinally(() -> cleanConnection(connection)))
                .get();

        result
                .mapFailure(
                        API.Case(API.$(), ex -> new LocalStorageFailure("Failed to create record for tool " + tool, ex))
                )
                .get();
    }

    @Override
    public void putString(final String tool, final String source, final String promptHash, final String value) {
        putString(tool, source, promptHash, 0, value);
    }
}
