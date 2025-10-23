package secondbrain.domain.persist;

import com.azure.core.util.MetricsOptions;
import com.azure.cosmos.*;
import com.azure.cosmos.models.*;
import io.vavr.API;
import io.vavr.control.Try;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.encryption.Encryptor;
import secondbrain.domain.exceptionhandling.ExceptionHandler;
import secondbrain.domain.exceptions.LocalStorageFailure;
import secondbrain.domain.json.JsonDeserializer;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Azure Cosmos DB implementation of LocalStorage for caching API calls and LLM results.
 */
@ApplicationScoped
public class CosmosLocalStorage implements LocalStorage {

    private static final int DEFAULT_TTL_SECONDS = 86400;
    private static final int MAX_FAILURES = 5;

    private final AtomicInteger totalReads = new AtomicInteger();
    private final AtomicInteger totalCacheHits = new AtomicInteger();
    private final AtomicInteger totalFailures = new AtomicInteger();

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
    @ConfigProperty(name = "sb.cosmos.endpoint")
    private Optional<String> cosmosEndpoint;

    @Inject
    @ConfigProperty(name = "sb.cosmos.key")
    private Optional<String> cosmosKey;

    @Inject
    @ConfigProperty(name = "sb.cosmos.database", defaultValue = "secondbrain")
    private Optional<String> databaseName;

    @Inject
    @ConfigProperty(name = "sb.cosmos.container", defaultValue = "localstorage")
    private Optional<String> containerName;

    @Inject
    private JsonDeserializer jsonDeserializer;

    @Inject
    private ExceptionHandler exceptionHandler;

    @Inject
    private Logger logger;

    @Inject
    private Encryptor encryptor;

    private CosmosClient cosmosClient;
    private CosmosContainer container;

    @PostConstruct
    public void postConstruct() {
        logger.info("Initializing Cosmos DB local storage");
        synchronized (CosmosLocalStorage.class) {
            if (cosmosClient == null) {
                Try.run(this::initializeCosmosClient)
                        .onFailure(ex -> logger.warning(exceptionHandler.getExceptionMessage(ex)));
            }
        }
        logger.info("Initialized Cosmos DB local storage");
    }

    @PreDestroy
    public void preDestroy() {
        synchronized (CosmosLocalStorage.class) {
            if (cosmosClient != null) {
                Try.run(cosmosClient::close)
                        .onFailure(ex -> logger.warning(exceptionHandler.getExceptionMessage(ex)));
                cosmosClient = null;
                container = null;
            }
        }

        if (totalReads.get() > 0) {
            logger.info("Cache hits percentage: " + getCacheHitsPercentage() + "%");
        }
    }

    private void resetConnection() {
        synchronized (CosmosLocalStorage.class) {
            logger.warning("Resetting Cosmos DB connection");
            totalFailures.set(0);
            preDestroy();
            postConstruct();
        }
    }

    private float getCacheHitsPercentage() {
        return totalReads.get() > 0 ? (float) totalCacheHits.get() / totalReads.get() * 100 : 0;
    }

    private void initializeCosmosClient() {
        if (cosmosEndpoint.isEmpty() || cosmosKey.isEmpty()) {
            throw new LocalStorageFailure("Cosmos DB endpoint and key must be configured");
        }

        final MetricsOptions metricsOptions = new CosmosMicrometerMetricsOptions();
        metricsOptions.setEnabled(false);

        final CosmosClientTelemetryConfig telemetryOptions = new CosmosClientTelemetryConfig();
        telemetryOptions.metricsOptions(metricsOptions);

        cosmosClient = new CosmosClientBuilder()
                .endpoint(cosmosEndpoint.get())
                .key(cosmosKey.get())
                .consistencyLevel(ConsistencyLevel.SESSION)
                .clientTelemetryConfig(telemetryOptions)
                .buildClient();

        cosmosClient.createDatabaseIfNotExists(databaseName.orElse("secondbrain"));

        final CosmosDatabase database = cosmosClient.getDatabase(databaseName.orElse("secondbrain"));

        // Create container if it doesn't exist
        final CosmosContainerProperties containerProperties = new CosmosContainerProperties(
                containerName.orElse("localstorage"),
                "/tool"
        );

        // Set TTL on the container to enable automatic deletion of expired items
        containerProperties.setDefaultTimeToLiveInSeconds(DEFAULT_TTL_SECONDS);

        Try.of(() -> database.createContainerIfNotExists(containerProperties))
                .onFailure(ex -> logger.warning("Failed to create container: " + exceptionHandler.getExceptionMessage(ex)));

        container = database.getContainer(containerName.orElse("localstorage"));
    }

    private boolean isDisabled() {
        return disable.isPresent() && Boolean.parseBoolean(disable.get());
    }

    private boolean isReadOnly() {
        return readOnly.isPresent() && Boolean.parseBoolean(readOnly.get());
    }

    private boolean isWriteOnly() {
        return writeOnly.isPresent() && Boolean.parseBoolean(writeOnly.get());
    }

    private String generateId(final String tool, final String source, final String promptHash) {
        return tool + "_" + source + "_" + promptHash;
    }

    @Override
    public CacheResult<String> getString(final String tool, final String source, final String promptHash) {
        synchronized (CosmosLocalStorage.class) {
            if (isDisabled() || isWriteOnly() || container == null) {
                return null;
            }

            if (totalFailures.get() > MAX_FAILURES) {
                resetConnection();
            }

            totalReads.incrementAndGet();

            final Try<CacheResult<String>> result = Try.of(() -> {
                        final String id = generateId(tool, source, promptHash);
                        final PartitionKey partitionKey = new PartitionKey(tool);

                        final CosmosItemResponse<CacheItem> response = container.readItem(
                                id,
                                partitionKey,
                                CacheItem.class
                        );

                        final CacheItem item = response.getItem();

                        // Check if item has expired (if timestamp is set)
                        if (item.timestamp != null && item.timestamp < Instant.now().getEpochSecond()) {
                            return new CacheResult<String>(null, false);
                        }

                        totalCacheHits.incrementAndGet();

                        final String decrypted = Try.of(() -> encryptor.decrypt(item.response))
                                .getOrNull();

                        return new CacheResult<String>(decrypted, true);
                    })
                    .recover(CosmosException.class, ex -> {
                        if (ex.getStatusCode() == 404) {
                            return new CacheResult<String>(null, false);
                        }
                        throw ex;
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
        if (isDisabled() || container == null) {
            return new CacheResult<String>(generateValue.generate(), false);
        }

        logger.fine("Getting string from cache for tool " + tool + " source " + source + " prompt " + promptHash);

        return Try
                .of(() -> getString(tool, source, promptHash))
                .filter(result -> StringUtils.isNotBlank(result.result()))
                .onSuccess(v -> logger.fine("Cache hit for tool " + tool + " source " + source + " prompt " + promptHash))
                .recover(result -> {
                    logger.fine("Cache lookup missed for tool " + tool + " source " + source + " prompt " + promptHash);
                    final String value = generateValue.generate();
                    putString(tool, source, promptHash, ttlSeconds, value);
                    return new CacheResult<String>(value, false);
                })
                .onFailure(LocalStorageFailure.class, ex -> logger.warning(exceptionHandler.getExceptionMessage(ex)))
                .recover(LocalStorageFailure.class, ex -> {
                    logger.fine("Cache lookup missed for tool " + tool + " source " + source + " prompt " + promptHash);
                    return new CacheResult<String>(generateValue.generate(), false);
                })
                .get();
    }

    @Override
    public CacheResult<String> getOrPutString(final String tool, final String source, final String promptHash, final GenerateValue<String> generateValue) {
        return getOrPutString(tool, source, promptHash, 0, generateValue);
    }

    @Override
    public <T> CacheResult<T> getOrPutObject(final String tool, final String source, final String promptHash, final int ttlSeconds, final Class<T> clazz, final GenerateValue<T> generateValue) {
        if (isDisabled() || container == null) {
            return new CacheResult<T>(generateValue.generate(), false);
        }

        logger.fine("Getting object from cache for tool " + tool + " source " + source + " prompt " + promptHash);

        return Try.of(() -> getString(tool, source, promptHash))
                .filter(result -> StringUtils.isNotBlank(result.result()))
                .onSuccess(v -> logger.fine("Cache hit for tool " + tool + " source " + source + " prompt " + promptHash))
                .mapTry(r -> new CacheResult<T>(jsonDeserializer.deserialize(r.result(), clazz), true))
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
                .onFailure(LocalStorageFailure.class, ex -> logger.warning(exceptionHandler.getExceptionMessage(ex)))
                .recover(LocalStorageFailure.class, ex -> {
                    logger.fine("Cache lookup missed for tool " + tool + " source " + source + " prompt " + promptHash);
                    return new CacheResult<T>(generateValue.generate(), false);
                })
                .get();
    }

    @Override
    public <T> CacheResult<T> getOrPutObject(final String tool, final String source, final String promptHash, final Class<T> clazz, final GenerateValue<T> generateValue) {
        return getOrPutObject(tool, source, promptHash, 0, clazz, generateValue);
    }

    @Override
    public void putString(final String tool, final String source, final String promptHash, final int ttlSeconds, final String value) {
        synchronized (CosmosLocalStorage.class) {
            if (isDisabled() || isReadOnly() || container == null) {
                return;
            }

            if (totalFailures.get() > MAX_FAILURES) {
                resetConnection();
            }

            final Try<CosmosItemResponse<CacheItem>> result = Try.of(() -> {
                        final String id = generateId(tool, source, promptHash);

                        // Set TTL and timestamp if specified
                        final int ttl = ttlSeconds > 0 ? ttlSeconds : -1;
                        final Long timestamp = ttlSeconds > 0 ? Instant.now().getEpochSecond() + ttlSeconds : null;

                        final String encrypted = encryptor.encrypt(value);

                        final CacheItem item = new CacheItem(id, tool, source, promptHash, encrypted, timestamp, ttl);

                        return container.upsertItem(item, new PartitionKey(tool), new CosmosItemRequestOptions());
                    })
                    .onFailure(ex -> totalFailures.incrementAndGet())
                    .onFailure(ex -> logger.warning(exceptionHandler.getExceptionMessage(ex)));

            result
                    .mapFailure(
                            API.Case(API.$(), ex -> new LocalStorageFailure("Failed to create record for tool " + tool, ex))
                    )
                    .get();
        }
    }

    @Override
    public void putString(final String tool, final String source, final String promptHash, final String value) {
        putString(tool, source, promptHash, 0, value);
    }

    // Record to represent the Cosmos DB document
    public record CacheItem(
            String id,
            String tool,
            String source,
            String promptHash,
            String response,
            Long timestamp,
            Integer ttl  // Time to live in seconds for Cosmos DB automatic expiration
    ) {
    }
}
