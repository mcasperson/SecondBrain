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
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.encryption.Encryptor;
import secondbrain.domain.exceptionhandling.ExceptionHandler;
import secondbrain.domain.exceptions.LocalStorageFailure;
import secondbrain.domain.exceptions.SerializationFailed;
import secondbrain.domain.json.JsonDeserializer;
import secondbrain.domain.zip.Zipper;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static com.pivovarit.collectors.ParallelCollectors.Batching.parallelToStream;

/**
 * Azure Cosmos DB implementation of LocalStorage for caching API calls and LLM results.
 * This implementation uses a local file cache to potentially speed up reads and reduce Cosmos DB read costs.
 */
@ApplicationScoped
public class CosmosLocalStorage implements LocalStorage {

    private static final Pattern LOCAL_CACHE_TIMESTAMP = Pattern.compile("(.*?)\\.cache\\.(\\d+)");
    private static final int BATCH_SIZE = 100;
    private static final String CONTAINER_NAME = "localstoragezipped";
    private static final String DATABASE_NAME = "secondbrain";
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
    @ConfigProperty(name = "sb.cosmos.database", defaultValue = DATABASE_NAME)
    private Optional<String> databaseName;

    @Inject
    @ConfigProperty(name = "sb.cosmos.container", defaultValue = CONTAINER_NAME)
    private Optional<String> containerName;

    @Inject
    @ConfigProperty(name = "sb.cosmos.localcache", defaultValue = "cosmoscache")
    private Optional<String> cosmosCache;

    @Inject
    private JsonDeserializer jsonDeserializer;

    @Inject
    private ExceptionHandler exceptionHandler;

    @Inject
    private Logger logger;

    @Inject
    private Encryptor encryptor;

    @Inject
    private Zipper zipper;

    @Inject
    private LocalStorageReadWrite localStorageReadWrite;

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

        cosmosClient.createDatabaseIfNotExists(databaseName.orElse(DATABASE_NAME));

        final CosmosDatabase database = cosmosClient.getDatabase(databaseName.orElse(DATABASE_NAME));

        // Create container if it doesn't exist
        final CosmosContainerProperties containerProperties = new CosmosContainerProperties(
                containerName.orElse(CONTAINER_NAME),
                "/tool"
        );

        // Set TTL on the container to enable automatic deletion of expired items
        containerProperties.setDefaultTimeToLiveInSeconds(DEFAULT_TTL_SECONDS);

        Try.of(() -> database.createContainerIfNotExists(containerProperties))
                .onFailure(ex -> logger.warning("Failed to create container: " + exceptionHandler.getExceptionMessage(ex)));

        container = database.getContainer(containerName.orElse(CONTAINER_NAME));
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
        if (isDisabled() || isWriteOnly() || container == null) {
            return null;
        }

        if (totalFailures.get() > MAX_FAILURES) {
            resetConnection();
        }

        totalReads.incrementAndGet();

        final Try<CacheResult<String>> result = Try
                // Attempt to get from local cache first
                .of(() -> localStorageReadWrite.getString(tool, source, promptHash))
                // We only accept the local cache value if it's not blank
                .filter(Optional::isPresent)
                // Convert to a CacheResult
                .map(cache -> new CacheResult<String>(cache.get(), true))
                // If there was no locally cached value, get from Cosmos DB
                .recover(NoSuchElementException.class, ex -> {
                    final String id = generateId(tool, source, promptHash);
                    final PartitionKey partitionKey = new PartitionKey(tool);

                    final CosmosItemResponse<CacheItem> response = container.readItem(
                            id,
                            partitionKey,
                            CacheItem.class
                    );

                    // Check if item has expired (if timestamp is set)
                    if (response.getItem().timestamp != null) {
                        if (response.getItem().timestamp < Instant.now().getEpochSecond()) {
                            return new CacheResult<String>(null, false);
                        }
                    }

                    // If we are loading this from the remote cache, save it locally too
                    localStorageReadWrite.putString(tool, source, promptHash, getTimestamp(response.getItem().timestamp), response.getItem().response());

                    return new CacheResult<String>(response.getItem().response(), true);
                })
                // Decrypt and decompress the result if it was from cache
                .map(r -> {
                    if (!r.fromCache()) {
                        return r;
                    }

                    totalCacheHits.incrementAndGet();

                    final String original = Try.of(() -> encryptor.decrypt(r.result()))
                            .map(decrypted -> zipper.decompressString(decrypted))
                            .getOrNull();


                    return new CacheResult<String>(original, true);
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
        if (isDisabled() || container == null) {
            return new CacheResult<T[]>(generateValue.generate(), false);
        }

        logger.fine("Getting object from cache for tool " + tool + " source " + source + " prompt " + promptHash);

        final Executor executor = Executors.newVirtualThreadPerTaskExecutor();

        // Start by trying to load the full result from the local storage.
        // We assume local storage can save the complete array as a single item. Loading the full array is much more efficient.
        // The remote storage may not have this capability, so we fall back to loading each item individually.
        final Try<CacheResult<T[]>> localCacheTry = Try.of(() -> localStorageReadWrite.getString(tool, source, promptHash + "_all"))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .map(encryptor::decrypt)
                .map(zipper::decompressString)
                .map(result -> jsonDeserializer.deserialize(result, arrayClazz))
                .map(array -> new CacheResult<T[]>(array, true));

        if (localCacheTry.isSuccess()) {
            logger.fine("Cache hit for tool " + tool + " source " + source + " prompt " + promptHash + " in local cache");
            return localCacheTry.get();
        }


        return Try.of(() -> getString(tool, source, promptHash))
                .filter(result -> StringUtils.isNotBlank(result.result()))
                .onSuccess(v -> logger.fine("Cache hit for tool " + tool + " source " + source + " prompt " + promptHash))
                .mapTry(r -> NumberUtils.toInt(r.result(), 0))
                // The cached result is the number of items in the array.
                // We then loop over each index to get the individual items.
                .map(count -> IntStream.range(0, count)
                        .boxed()
                        .collect(parallelToStream(index -> getString(tool, source, promptHash + "_" + index), executor, BATCH_SIZE))
                        .map(r -> jsonDeserializer.deserialize(r.result(), clazz))
                        .toList()
                )
                // The list becomes an array
                .map(list -> list.toArray(ArrayUtils.newInstance(clazz, list.size())))
                // Persist the full array in local storage for next time
                .peek(array -> persistArrayResultLocal(tool, source, promptHash, ttlSeconds, array))
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

    private <T> void persistArrayResultLocal(final String tool, final String source, final String promptHash, final int ttlSeconds, final T[] value) {
        Try.of(() -> jsonDeserializer.serialize(value))
                .map(zipper::compressString)
                .map(encryptor::encrypt)
                .map(result -> localStorageReadWrite.putString(tool, source, promptHash + "_all", getTimestamp((long) ttlSeconds), result))
                .onFailure(ex -> logger.warning("Failed to persist full array result to local storage: " + exceptionHandler.getExceptionMessage(ex)));
    }

    private <T> CacheResult<T[]> persistArrayResult(final String tool, final String source, final String promptHash, final int ttlSeconds, final GenerateValue<T[]> generateValue) {
        final T[] value = generateValue.generate();

        // Persist the full array as a single compressed and encrypted item in local storage
        persistArrayResultLocal(tool, source, promptHash, ttlSeconds, value);

        // The result associated with the original hash is the count of items
        putString(
                tool,
                source,
                promptHash,
                ttlSeconds,
                value.length + "");

        // each item is persisted with an index suffix
        final Executor executor = Executors.newVirtualThreadPerTaskExecutor();
        IntStream.range(0, value.length)
                .boxed()
                .collect(parallelToStream(index -> {
                                    putString(
                                            tool,
                                            source,
                                            promptHash + "_" + index,
                                            ttlSeconds,
                                            jsonDeserializer.serialize(value[index]));

                                    return index;
                                },
                                executor,
                                BATCH_SIZE)
                );

        return new CacheResult<T[]>(value, false);
    }

    @Override
    public void putString(final String tool, final String source, final String promptHash, final int ttlSeconds, final String value) {
        if (isDisabled() || isReadOnly() || container == null) {
            return;
        }

        if (totalFailures.get() > MAX_FAILURES) {
            resetConnection();
        }

        final Try<CosmosItemResponse<CacheItem>> result = Try.of(() -> zipper.compressString(value))
                .map(encryptor::encrypt)
                .map(encrypted -> localStorageReadWrite.putString(tool, source, promptHash, getTimestamp((long) ttlSeconds), encrypted))
                .map(encrypted -> new CacheItem(
                        generateId(tool, source, promptHash),
                        tool,
                        source,
                        promptHash,
                        encrypted,
                        getTimestamp((long) ttlSeconds),
                        sanitizeTtl(ttlSeconds)))
                .map(item -> container.upsertItem(item, new PartitionKey(tool), new CosmosItemRequestOptions()))
                .onFailure(ex -> totalFailures.incrementAndGet())
                .onFailure(ex -> logger.warning(exceptionHandler.getExceptionMessage(ex)));

        result
                .mapFailure(
                        API.Case(API.$(), ex -> new LocalStorageFailure("Failed to create record for tool " + tool, ex))
                )
                .get();
    }

    private Integer sanitizeTtl(final int ttlSeconds) {
        if (ttlSeconds > 0) {
            return ttlSeconds;
        }
        return -1;
    }

    private Long getTimestamp(final Long ttlSeconds) {
        if (ttlSeconds != null && ttlSeconds > 0) {
            return Instant.now().getEpochSecond() + ttlSeconds;
        }
        return null;
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
