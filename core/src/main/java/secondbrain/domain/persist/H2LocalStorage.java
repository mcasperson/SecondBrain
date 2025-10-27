package secondbrain.domain.persist;

import io.vavr.API;
import io.vavr.control.Try;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.exceptionhandling.ExceptionHandler;
import secondbrain.domain.exceptions.LocalStorageFailure;
import secondbrain.domain.exceptions.SerializationFailed;
import secondbrain.domain.json.JsonDeserializer;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.stream.IntStream;

import static com.google.common.base.Predicates.instanceOf;

/**
 * A very low effort caching solution that uses an H2 database to store the cache. It takes no additional configuration,
 * but H2 can be fickle, especially when running multiple clients simultaneously. The results store things like API
 * calls and LLM results, which are quite costly, so time spent retrying connections is still worth it.
 */
@ApplicationScoped
public class H2LocalStorage implements LocalStorage {

    private static final int MAX_RETRIES = 15;
    private static final int DELAY = 1000;
    private static final int MAX_FAILURES = 5;

    private final AtomicInteger totalReads = new AtomicInteger();
    private final AtomicInteger totalCacheHits = new AtomicInteger();
    private final AtomicInteger totalFailures = new AtomicInteger();

    @Inject
    @ConfigProperty(name = "sb.cache.disable")
    private Optional<String> disable;
    @Inject
    @ConfigProperty(name = "sb.cache.backup")
    private Optional<String> backup;
    @Inject
    @ConfigProperty(name = "sb.cache.readonly")
    private Optional<String> readOnly;
    @Inject
    @ConfigProperty(name = "sb.cache.autoserver", defaultValue = "true")
    private Optional<String> autoserver;
    @Inject
    @ConfigProperty(name = "sb.cache.writeonly")
    private Optional<String> writeOnly;
    @Inject
    @ConfigProperty(name = "sb.h2cache.path")
    private Optional<String> path;
    @Inject
    private JsonDeserializer jsonDeserializer;
    @Inject
    private ExceptionHandler exceptionHandler;
    @Inject
    private Logger logger;

    private Connection connection;

    @PostConstruct
    public void postConstruct() {
        logger.info("Initializing local storage");
        synchronized (H2LocalStorage.class) {
            if (connection == null) {
                backupDatabase();
                this.connection = Try.of(this::getConnection)
                        .onFailure(ex -> logger.warning(exceptionHandler.getExceptionMessage(ex)))
                        .onSuccess(conn -> deleteExpired())
                        .getOrNull();
            }
        }
        logger.info("Initialized local storage");
    }

    @PreDestroy
    public void preDestroy() {
        synchronized (H2LocalStorage.class) {
            if (connection != null) {
                cleanConnection(connection);
                connection = null;
            }
        }

        if (totalReads.get() > 0) {
            logger.info("Cache hits percentage: " + getCacheHitsPercentage() + "%");
        }
    }

    private void resetConnection() {
        synchronized (H2LocalStorage.class) {
            logger.warning("Resetting H2 connection");
            totalFailures.set(0);
            preDestroy();
            postConstruct();
        }
    }

    private float getCacheHitsPercentage() {
        return totalReads.get() > 0 ? (float) totalCacheHits.get() / totalReads.get() * 100 : 0;
    }

    private Connection getConnection() {
        return getConnection(0);
    }

    private Connection getConnection(final int count) {
        if (count > MAX_RETRIES) {
            throw new LocalStorageFailure("Failed to get connection after " + MAX_RETRIES + " attempts");
        }

        if (count > 0) {
            logger.info("Retrying connection to local storage " + count + " of " + MAX_RETRIES);
            // Sleep with some jitter
            Try.run(() -> Thread.sleep(DELAY + (int) (Math.random() * 1000)));
        }

        return Try.of(() -> DriverManager.getConnection(getConnectionString()))
                .recover(ex -> {
                    logger.warning(exceptionHandler.getExceptionMessage(ex));

                    if (count < MAX_RETRIES) {
                        return getConnection(count + 1);
                    }

                    throw new LocalStorageFailure("Failed to get connection after " + MAX_RETRIES + " attempts", ex);
                })
                .get();
    }

    private void cleanConnection(final Connection connection) {
        if (connection != null) {
            Try.run(connection::close)
                    .onFailure(ex -> logger.warning(exceptionHandler.getExceptionMessage(ex)));
        }
    }

    private String getDatabasePath() {
        return path
                .map(p -> Paths.get(p, "localstoragev2").toAbsolutePath().toString())
                .orElse("./localstoragev2");
    }

    /**
     * The cache is quite useful, and also easy to corrupt. So we keep a few copies of the database around in case we
     * corrupt it.
     */
    private void backupDatabase() {
        if (!backup.map(Boolean::parseBoolean).orElse(false)) {
            return;
        }

        Try.run(() -> Files.delete(Paths.get(getDatabasePath() + ".mv.db.backup.5")));

        for (int i = 4; i > 0; i--) {
            final int index = i;
            Try.run(() -> Files.move(
                    Paths.get(getDatabasePath() + ".mv.db.backup." + index),
                    Paths.get(getDatabasePath() + ".mv.db.backup." + (index + 1))));
        }

        Try.run(() -> Files.copy(
                Paths.get(getDatabasePath() + ".mv.db"),
                Paths.get(getDatabasePath() + ".mv.db.backup.1")));
    }

    private String getConnectionString() {
        return "jdbc:h2:file:" + getDatabasePath() + ";" + """
                AUTO_SERVER=""" + getAutoServer() + ";" + """
                AUTO_RECONNECT=TRUE;
                AUTO_COMPACT_FILL_RATE=50;
                INIT=CREATE SCHEMA IF NOT EXISTS SECONDBRAIN\\;
                SET SCHEMA SECONDBRAIN\\;
                CREATE TABLE IF NOT EXISTS SECONDBRAIN.LOCAL_STORAGE
                (tool VARCHAR(100) NOT NULL,
                source VARCHAR(1024) NOT NULL,
                prompt_hash VARCHAR(1024) NOT NULL,
                response CLOB NOT NULL,
                timestamp TIMESTAMP DEFAULT NULL)\\;
                CREATE INDEX IF NOT EXISTS idx_timestamp ON SECONDBRAIN.LOCAL_STORAGE(timestamp)\\;
                CREATE INDEX IF NOT EXISTS idx_tool ON SECONDBRAIN.LOCAL_STORAGE(tool)\\;
                CREATE INDEX IF NOT EXISTS idx_source ON SECONDBRAIN.LOCAL_STORAGE(source)\\;
                CREATE INDEX IF NOT EXISTS idx_prompt_hash ON SECONDBRAIN.LOCAL_STORAGE(prompt_hash);""".stripIndent().replaceAll("\n", "");
    }

    /**
     * The auto server functionality doesn't work in Docker.
     */
    private String getAutoServer() {
        return autoserver != null && autoserver.isPresent() && Boolean.parseBoolean(autoserver.get())
                ? "TRUE"
                : "FALSE";
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

    private boolean deleteExpired() {
        synchronized (H2LocalStorage.class) {
            if (isDisabled() || connection == null) {
                return false;
            }

            if (totalFailures.get() > MAX_FAILURES) {
                resetConnection();
            }

            final Try<Integer> result = Try
                    .withResources(() -> connection.prepareStatement("""
                            DELETE FROM LOCAL_STORAGE
                            WHERE timestamp IS NOT NULL
                            AND timestamp < CURRENT_TIMESTAMP""".stripIndent()))
                    .of(PreparedStatement::executeUpdate)
                    .onFailure(ex -> totalFailures.incrementAndGet());

            return result
                    .mapFailure(
                            API.Case(API.$(), ex -> new LocalStorageFailure("Failed to delete old records", ex))
                    )
                    .isSuccess();
        }
    }

    /**
     * A best effort to get a cached string from the database. This method will silently fail or immediately return
     * if the cache is disabled or if there was an exception attempting to get the value.
     */
    @Override
    public CacheResult<String> getString(final String tool, final String source, final String promptHash) {
        synchronized (H2LocalStorage.class) {
            if (isDisabled() || isWriteOnly() || this.connection == null) {
                return null;
            }

            // Fail a few times before attempting to reset the connection
            if (totalFailures.get() > MAX_FAILURES) {
                resetConnection();
            }

            totalReads.incrementAndGet();

            final Try<CacheResult<String>> result = Try.withResources(() -> connection.prepareStatement("""
                            SELECT response FROM LOCAL_STORAGE
                                            WHERE tool = ?
                                            AND source = ?
                                            AND prompt_hash = ?
                                            AND (timestamp IS NULL OR timestamp > CURRENT_TIMESTAMP)""".stripIndent()))
                    .of(preparedStatement -> {
                        preparedStatement.setString(1, tool);
                        preparedStatement.setString(2, source);
                        preparedStatement.setString(3, promptHash);
                        return Try.withResources(preparedStatement::executeQuery)
                                .of(resultSet -> {
                                    if (resultSet.next()) {
                                        totalCacheHits.incrementAndGet();
                                        return new CacheResult<String>(resultSet.getString(1), true);
                                    }
                                    return new CacheResult<String>(null, false);
                                }).get();

                    })
                    .onFailure(ex -> totalFailures.incrementAndGet())
                    .onFailure(ex -> logger.warning(exceptionHandler.getExceptionMessage(ex)));

            return result
                    .mapFailure(
                            API.Case(API.$(), ex -> new LocalStorageFailure("Failed to get record", ex))
                    )
                    .get();
        }
    }

    @Override
    public CacheResult<String> getOrPutString(final String tool, final String source, final String promptHash, final int ttlSeconds, final GenerateValue<String> generateValue) {
        if (isDisabled() || connection == null) {
            return new CacheResult<String>(generateValue.generate(), false);
        }

        logger.fine("Getting string from cache for tool " + tool + " source " + source + " prompt " + promptHash);

        return Try
                .of(() -> getString(tool, source, promptHash))
                // a cache miss means the string is empty, so we throw an exception
                .filter(result -> StringUtils.isNotBlank(result.result()))
                // cache hit
                .onSuccess(v -> logger.fine("Cache hit for tool " + tool + " source " + source + " prompt " + promptHash))
                // recover from a cache miss by generating the value and saving it
                .recover(result -> {
                    logger.fine("Cache lookup missed for tool " + tool + " source " + source + " prompt " + promptHash);
                    final String value = generateValue.generate();
                    putString(tool, source, promptHash, ttlSeconds, value);
                    return new CacheResult<String>(value, false);
                })
                /*
                    Exceptions are swallowed here because caching is just a best effort. But
                    we still need to know if something went wrong.
                 */
                .onFailure(LocalStorageFailure.class, ex -> logger.warning(exceptionHandler.getExceptionMessage(ex)))
                // If there was an error with the local storage, bypass it and generate the value
                .recover(LocalStorageFailure.class, ex -> {
                    logger.fine("Cache lookup missed for tool " + tool + " source " + source + " prompt " + promptHash);
                    return new CacheResult<String>(generateValue.generate(), false);
                })
                // For all other errors, we return the value or rethrow the exception
                .get();
    }

    @Override
    public CacheResult<String> getOrPutString(final String tool, final String source, final String promptHash, final GenerateValue<String> generateValue) {
        return getOrPutString(tool, source, promptHash, 0, generateValue);
    }

    @Override
    public <T> CacheResult<T> getOrPutObject(final String tool, final String source, final String promptHash, final int ttlSeconds, final Class<T> clazz, final GenerateValue<T> generateValue) {
        if (isDisabled() || connection == null) {
            return new CacheResult<T>(generateValue.generate(), false);
        }

        logger.fine("Getting object from cache for tool " + tool + " source " + source + " prompt " + promptHash);

        return Try.of(() -> getString(tool, source, promptHash))
                // a cache miss means the string is empty, so we throw an exception
                .filter(result -> StringUtils.isNotBlank(result.result()))
                // a cache hit means we deserialize the result
                .onSuccess(v -> logger.fine("Cache hit for tool " + tool + " source " + source + " prompt " + promptHash))
                .mapTry(r -> new CacheResult<T>(jsonDeserializer.deserialize(r.result(), clazz), true))
                // a cache miss means we call the API and then save the result in the cache
                .recoverWith(ex -> Try.of(() -> {
                            logger.fine("Cache lookup missed for tool " + tool + " source " + source + " prompt " + promptHash);
                            logger.fine("Exception: " + exceptionHandler.getExceptionMessage(ex));
                            final T value = generateValue.generate();
                            putString(
                                    tool,
                                    source,
                                    promptHash,
                                    ttlSeconds,
                                    jsonDeserializer.serialize(value));
                            return new CacheResult<T>(value, false);
                        })
                )
                /*
                    Exceptions are swallowed here because caching is just a best effort. But
                    we still need to know if something went wrong.
                 */
                .onFailure(LocalStorageFailure.class, ex -> logger.warning(exceptionHandler.getExceptionMessage(ex)))
                .onFailure(SerializationFailed.class, ex -> logger.warning(exceptionHandler.getExceptionMessage(ex)))
                .recover(LocalStorageFailure.class, ex -> {
                    logger.fine("Cache lookup missed for tool " + tool + " source " + source + " prompt " + promptHash);
                    return new CacheResult<T>(generateValue.generate(), false);
                })
                // Gracefully deal with an object that can't be serialized
                .recover(SerializationFailed.class, ex -> new CacheResult<T>(generateValue.generate(), false))
                .get();
    }

    @Override
    public <T> CacheResult<T> getOrPutObject(final String tool, final String source, final String promptHash, final Class<T> clazz, final GenerateValue<T> generateValue) {
        return getOrPutObject(tool, source, promptHash, 0, clazz, generateValue);
    }

    @Override
    public <T> CacheResult<T[]> getOrPutObjectArray(final String tool, final String source, final String promptHash, final int ttlSeconds, final Class<T> clazz, final Class<T[]> arrayClazz, final GenerateValue<T[]> generateValue) {
        if (isDisabled() || connection == null) {
            return new CacheResult<T[]>(generateValue.generate(), false);
        }

        logger.fine("Getting object from cache for tool " + tool + " source " + source + " prompt " + promptHash);

        return Try.of(() -> getString(tool, source, promptHash))
                .filter(result -> StringUtils.isNotBlank(result.result()))
                .onSuccess(v -> logger.fine("Cache hit for tool " + tool + " source " + source + " prompt " + promptHash))
                .mapTry(r -> NumberUtils.toInt(r.result(), 0))
                // The cached result is the number of items in the array.
                // We then loop pver each index to get the individual items.
                .map(count -> IntStream.range(0, count)
                        .mapToObj(index -> getString(tool, source, promptHash + "_" + index))
                        .map(r -> jsonDeserializer.deserialize(r.result(), clazz))
                        .toList()
                )
                // The list becomes an array
                .map(list -> list.toArray(ArrayUtils.newInstance(clazz, list.size())))
                // The array is wrapped in a CacheResult
                .map(array -> new CacheResult<T[]>(array, true))
                .recoverWith(ex -> Try.of(() -> {
                            logger.fine("Cache lookup missed for tool " + tool + " source " + source + " prompt " + promptHash);
                            logger.fine("Exception: " + exceptionHandler.getExceptionMessage(ex));
                            return persistArrayResult(tool, source, promptHash, ttlSeconds, generateValue);
                        })
                )
                .onFailure(LocalStorageFailure.class, ex -> logger.warning(exceptionHandler.getExceptionMessage(ex)))
                .recover(LocalStorageFailure.class, ex -> {
                    logger.fine("Cache lookup missed for tool " + tool + " source " + source + " prompt " + promptHash);
                    return new CacheResult<T[]>(generateValue.generate(), false);
                })
                .get();
    }

    private <T> CacheResult<T[]> persistArrayResult(final String tool, final String source, final String promptHash, final int ttlSeconds, final GenerateValue<T[]> generateValue) {
        final T[] value = generateValue.generate();

        // The result associated with the original hash is the count of items
        putString(
                tool,
                source,
                promptHash,
                ttlSeconds,
                value.length + "");

        // each item is persisted with an index suffix
        for (int i = 0; i < value.length; i++) {
            putString(
                    tool,
                    source,
                    promptHash + "_" + i,
                    ttlSeconds,
                    jsonDeserializer.serialize(value[i]));
        }

        return new CacheResult<T[]>(value, false);
    }

    @Override
    public void putString(final String tool, final String source, final String promptHash, final int ttlSeconds, final String response) {
        synchronized (H2LocalStorage.class) {
            if (isDisabled() || isReadOnly() || this.connection == null) {
                return;
            }

            if (totalFailures.get() > MAX_FAILURES) {
                resetConnection();
            }

            final Try<PreparedStatement> result = Try.withResources(() -> connection.prepareStatement("""
                            INSERT INTO LOCAL_STORAGE (tool, source, prompt_hash, response, timestamp)
                            VALUES (?, ?, ?, ?, ?)""".stripIndent()))
                    .of(preparedStatement -> {
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
                    .onFailure(ex -> totalFailures.incrementAndGet())
                    .onFailure(ex -> logger.warning(exceptionHandler.getExceptionMessage(ex)));

            result
                    .mapFailure(
                            API.Case(API.$(instanceOf(LocalStorageFailure.class)), ex -> ex),
                            API.Case(API.$(), ex -> new LocalStorageFailure("Failed to create record for tool " + tool, ex))
                    )
                    .get();
        }
    }

    @Override
    public void putString(final String tool, final String source, final String promptHash, final String value) {
        putString(tool, source, promptHash, 0, value);
    }
}
