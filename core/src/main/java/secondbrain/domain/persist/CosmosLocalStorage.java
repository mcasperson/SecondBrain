package secondbrain.domain.persist;

import com.azure.core.util.MetricsOptions;
import com.azure.cosmos.*;
import com.azure.cosmos.models.*;
import io.smallrye.common.annotation.Identifier;
import io.vavr.API;
import io.vavr.control.Try;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Startup;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.encryption.Encryptor;
import secondbrain.domain.exceptionhandling.ExceptionHandler;
import secondbrain.domain.exceptions.*;
import secondbrain.domain.json.JsonDeserializer;
import secondbrain.domain.persist.config.LocalStorageCacheDisable;
import secondbrain.domain.persist.config.LocalStorageCacheReadOnly;
import secondbrain.domain.persist.config.LocalStorageCacheWriteOnly;
import secondbrain.domain.persist.config.LocalStorageDisableTool;
import secondbrain.domain.sanitize.SanitizeDocument;
import secondbrain.domain.concurrency.SharedVirtualThreadExecutor;
import secondbrain.domain.zip.Zipper;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import java.util.stream.IntStream;

import static com.pivovarit.collectors.ParallelCollectors.Batching.parallelToStream;

/**
 * Azure Cosmos DB implementation of LocalStorage for caching API calls and LLM results.
 * This implementation uses a local file cache to potentially speed up reads and reduce Cosmos DB read costs.
 */
@ApplicationScoped
public class CosmosLocalStorage implements LocalStorage {

    private static final int BATCH_SIZE = 100;
    private static final String CONTAINER_NAME = "localstoragezipped";
    private static final String DATABASE_NAME = "secondbrain";
    private static final int DEFAULT_TTL_SECONDS = 86400;
    private static final int MAX_FAILURES = 5; // 2 MB
    private static final int SPLIT_ITEM_SIZE_BYTES = 1024 * 1024;

    private final AtomicInteger totalReads = new AtomicInteger();
    private final AtomicInteger totalCacheHits = new AtomicInteger();
    private final AtomicInteger totalFailures = new AtomicInteger();
    private final List<Future<?>> pendingWrites = Collections.synchronizedList(new ArrayList<>());

    @Inject
    private LocalStorageDisableTool localStorageDisableTool;

    @Inject
    private LocalStorageCacheDisable localStorageCacheDisable;

    @Inject
    private LocalStorageCacheReadOnly localStorageCacheReadOnly;

    @Inject
    private LocalStorageCacheWriteOnly localStorageCacheWriteOnly;

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

    /**
     * Set to false when using vnext cosmosdb docker image
     */
    @Inject
    @ConfigProperty(name = "sb.cosmos.autodiscovery", defaultValue = "true")
    private boolean autoDiscovery;

    /**
     * Set to true when using vnext cosmosdb docker image
     */
    @Inject
    @ConfigProperty(name = "sb.cosmos.gatewayMode", defaultValue = "false")
    private boolean gatewayMode;

    @Inject
    private JsonDeserializer jsonDeserializer;

    @Inject
    private ExceptionHandler exceptionHandler;

    @Inject
    private Logger logger;

    @Inject
    @Identifier("AES")
    private Encryptor encryptor;

    @Inject
    @jakarta.enterprise.inject.Any
    private Instance<Encryptor> encryptors;

    @Inject
    @Identifier("ApacheCommonsZStdZipper")
    private Zipper zipper;

    @Inject
    @jakarta.enterprise.inject.Any
    private Instance<Zipper> zippers;

    @Inject
    private LocalStorageReadWrite localStorageReadWrite;

    @Inject
    private SharedVirtualThreadExecutor sharedExecutor;

    @Nullable
    private CosmosClient cosmosClient;

    @Nullable
    private CosmosContainer container;

    @Inject
    @Identifier("financialLocationContactRedaction")
    private SanitizeDocument sanitizeDocument;

    @Inject
    private SharedVirtualThreadExecutor sharedVirtualThreadExecutor;

    @PostConstruct
    public void postConstruct() {
        logger.fine("Initializing Cosmos DB local storage");
        synchronized (CosmosLocalStorage.class) {
            if (cosmosClient == null) {
                initializeCosmosClient();
            }
        }
        logger.fine("Initialized Cosmos DB local storage");
    }

    @PreDestroy
    public void preDestroy() {
        flush();

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

    public void flush() {
        // Collect and clear pending futures under the lock, then wait outside it.
        // Waiting inside the lock would deadlock: putStringSync also needs the same lock,
        // so it can never complete while flush() holds it and blocks on f.get().
        final List<Future<?>> toWait;
        synchronized (CosmosLocalStorage.class) {
            pendingWrites.removeIf(Future::isDone);
            toWait = List.copyOf(pendingWrites);
            pendingWrites.clear();
        }
        logger.fine("Waiting for " + toWait.size() + " pending background writes to complete");
        for (final Future<?> f : toWait) {
            Try.run(() -> f.get(2, TimeUnit.MINUTES))
                    .onFailure(ex -> logger.warning("Error waiting for pending write: " + exceptionHandler.getExceptionMessage(ex)));
        }
    }

    private void resetConnection() {
        synchronized (CosmosLocalStorage.class) {
            logger.warning("Resetting Cosmos DB connection");
            totalFailures.set(0);
            flush();
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

        // Different versions of the cosmos DB need different options.
        // The vnext version (which is the version of the docker image used on a mac) disables auto discovery and enables gateway mode.
        // The latest version (used on Linux and in production) enables discovery and disables gateway mode.
        final CosmosClientBuilder builder = new CosmosClientBuilder()
                .endpoint(cosmosEndpoint.get())
                .key(cosmosKey.get())
                .consistencyLevel(ConsistencyLevel.SESSION)
                .clientTelemetryConfig(telemetryOptions)
                .endpointDiscoveryEnabled(autoDiscovery)
                .contentResponseOnWriteEnabled(false);

        cosmosClient = (gatewayMode ? builder.gatewayMode() : builder)
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

    private String generateId(final String tool, final String source, final String promptHash) {
        return tool + "_" + source + "_" + promptHash;
    }

    @Override
    @Nullable
    public CacheResult<String> getString(final String tool, final String source, final String promptHash) {
        synchronized (CosmosLocalStorage.class) {
            if (localStorageCacheDisable.isDisabled() || localStorageDisableTool.isToolDisabled(tool) || localStorageCacheWriteOnly.isWriteOnly() || container == null) {
                return new CacheResult<String>(null, false);
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
                    .recover(NoSuchElementException.class, ex -> loadFromDatabase(tool, source, promptHash))
                    // Decrypt and decompress the result if it was from cache
                    .map(value -> unpack(value, tool, source))
                    // If the item is not found, return a CacheResult with null
                    .recover(CosmosException.class, this::handleError)
                    // Track failures
                    .onFailure(ex -> totalFailures.incrementAndGet())
                    // Log errors
                    .onFailure(ex -> logger.warning(exceptionHandler.getExceptionMessage(ex)));

            final CacheResult<String> directResult = result
                    .mapFailure(
                            API.Case(API.$(), ex -> new LocalStorageFailure("Failed to get record", ex))
                    )
                    .get();

            // If no direct result found, check whether chunked items exist and reassemble
            if (directResult == null || StringUtils.isBlank(directResult.result())) {
                return reassembleChunks(tool, source, promptHash);
            }

            return directResult;
        }
    }

    private CacheResult<String> handleError(final CosmosException ex) {
        if (ex.getStatusCode() == 404) {
            return new CacheResult<String>(null, false);
        }
        throw ex;
    }

    /**
     * Attempts to find and reassemble chunked items for the given promptHash.
     * Chunks are stored with suffix "_chunk_<index>", and the total count is stored under "_chunked_size".
     */
    private CacheResult<String> reassembleChunks(final String tool, final String source, final String promptHash) {
        // Look up the total chunk count saved alongside the chunks
        final CacheResult<String> sizeResult = Try
                .of(() -> localStorageReadWrite.getString(tool, source, promptHash + "_chunked_size"))
                .filter(Optional::isPresent)
                .map(cache -> new CacheResult<String>(cache.get(), true))
                .recover(NoSuchElementException.class, ex -> loadFromDatabase(tool, source, promptHash + "_chunked_size"))
                .map(value -> unpack(value, tool, source))
                .recover(CosmosException.class, this::handleError)
                .getOrNull();

        if (sizeResult == null || StringUtils.isBlank(sizeResult.result())) {
            return new CacheResult<String>(null, false);
        }

        final int total = NumberUtils.toInt(sizeResult.result(), 0);
        if (total <= 0) {
            return new CacheResult<String>(null, false);
        }

        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < total; i++) {
            final String key = promptHash + "_chunk_" + i;
            final CacheResult<String> chunk = Try
                    .of(() -> localStorageReadWrite.getString(tool, source, key))
                    .filter(Optional::isPresent)
                    .map(cache -> new CacheResult<String>(cache.get(), true))
                    .recover(NoSuchElementException.class, ex -> loadFromDatabase(tool, source, key))
                    .map(value -> unpack(value, tool, source))
                    .recover(CosmosException.class, this::handleError)
                    .getOrNull();

            if (chunk == null || StringUtils.isBlank(chunk.result())) {
                logger.warning("Missing chunk " + i + " of " + total + " for tool " + tool + " source " + source + " prompt " + promptHash);
                return new CacheResult<String>(null, false);
            }
            sb.append(chunk.result());
        }

        logger.fine("Reassembled " + total + " chunks for tool " + tool + " source " + source + " prompt " + promptHash);
        return new CacheResult<>(sb.toString(), true);
    }

    @SuppressWarnings("NullAway")
    @Nullable
    private CacheResult<String> unpack(final CacheResult<String> result, final String tool, final String source) {
        if (!result.fromCache()) {
            return result;
        }

        if (StringUtils.isBlank(result.result())) {
            return null;
        }

        totalCacheHits.incrementAndGet();

        final String original = Try.of(() -> this.decryptString(result.result()))
                .map(this::decompressString)
                .onFailure(ex -> logger.warning("Failed to unpack cached string for tool " + tool
                        + " and source " + source + "."
                        + " This is likely due to an invalid password in the sb.encryption.password setting or a change to the sb.encryption.salt setting."
                        + " The cached value will be ignored and the value will be obtained from the source: "
                        + exceptionHandler.getExceptionMessage(ex)))
                .getOrNull();


        return new CacheResult<String>(original, true);
    }

    private String decompressString(final String compressed) {
        final String zipperClassName = zipper.getClass().getName();

        // Try all available zippers in order, returning the first one that succeeds.
        // This is to support backwards compatibility with older versions of the application.
        return zippers.stream()
                .sorted((o1, o2) -> {
                    final String o1ClassName = o1.getClass().getName();
                    final String o2ClassName = o2.getClass().getName();

                    if (o1ClassName.equals(o2ClassName)) {
                        return 0;
                    }

                    // Prioritize the object used to compress the results by default
                    if (o1ClassName.equals(zipperClassName)) {
                        return -1;
                    }

                    if (o2ClassName.equals(zipperClassName)) {
                        return 1;
                    }

                    return o1ClassName.compareTo(o2ClassName);
                })
                .map(z -> Try.of(() -> z.decompressString(compressed)))
                .filter(t -> t.isSuccess() && t.get() != null)
                .map(Try::get)
                .findFirst()
                .orElseThrow(DecompressionFailed::new);
    }

    private String decryptString(final String encrypted) {
        final String encryptorClassName = encryptor.getClass().getName();

        // Try all available encryptors in order, returning the first one that succeeds.
        // This is to support backwards compatibility with older versions of the application.
        return encryptors.stream()
                .sorted((o1, o2) -> {
                    final String o1ClassName = o1.getClass().getName();
                    final String o2ClassName = o2.getClass().getName();

                    if (o1ClassName.equals(o2ClassName)) {
                        return 0;
                    }

                    // Prioritize the object used to decrypt the results by default
                    if (o1ClassName.equals(encryptorClassName)) {
                        return -1;
                    }

                    if (o2ClassName.equals(encryptorClassName)) {
                        return 1;
                    }

                    return o1ClassName.compareTo(o2ClassName);
                })
                .map(z -> Try.of(() -> z.decrypt(encrypted)))
                .filter(t -> t.isSuccess() && t.get() != null)
                .map(Try::get)
                .findFirst()
                .orElseThrow(DecryptionFailed::new);
    }

    private CacheResult<String> loadFromDatabase(final String tool, final String source, final String promptHash) {
        return Try.withResources(() -> new TimedOperation("load from Cosmos DB for " + tool + " " + source))
                .of(t -> loadFromDatabaseTimed(tool, source, promptHash))
                .get();
    }

    private CacheResult<String> loadFromDatabaseTimed(final String tool, final String source, final String promptHash) {
        if (container == null) {
            throw new LocalStorageFailure("Cosmos DB container is not initialized");
        }

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
        localStorageReadWrite.putString(tool, source, promptHash, response.getItem().timestamp, response.getItem().response());

        return new CacheResult<String>(response.getItem().response(), true);
    }

    @Override
    public CacheResult<String> getOrPutString(final String tool, final String source, final String promptHash, final long ttlSeconds, final GenerateValue<String> generateValue) {
        return Try.withResources(() -> new TimedOperation("Cached string result for " + tool + " " + source))
                .of(t -> getOrPutStringTimed(tool, source, promptHash, ttlSeconds, generateValue))
                .get();
    }

    private CacheResult<String> getOrPutStringTimed(final String tool, final String source, final String promptHash, final long ttlSeconds, final GenerateValue<String> generateValue) {
        if (localStorageCacheDisable.isDisabled() || container == null) {
            return new CacheResult<String>(generateValue.generate(), false);
        }

        logger.fine("Getting string from cache for tool " + tool + " source " + source + " prompt " + promptHash);

        return Try
                .of(() -> getString(tool, source, promptHash))
                .filter(result -> result != null && StringUtils.isNotBlank(result.result()))
                .onSuccess(v -> logger.fine("Cache hit for tool " + tool + " source " + source + " prompt " + promptHash))
                .recover(result -> {
                    logger.fine("Cache lookup missed for tool " + tool + " source " + source + " prompt " + promptHash);
                    final String value = generateValue.generate();
                    if (StringUtils.isNotBlank(value)) {
                        putString(tool, source, promptHash, ttlSeconds, value);
                    }
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
    public <T> CacheResult<T> getOrPutObject(final String tool, final String source, final String promptHash, final long ttlSeconds, final Class<T> clazz, final GenerateValue<T> generateValue) {
        return getOrPutPrivate(tool, source, promptHash, ttlSeconds, generateValue, json -> jsonDeserializer.deserialize(json, clazz));
    }

    @Override
    public <T> CacheResult<T> getOrPutObject(final String tool, final String source, final String promptHash, final Class<T> clazz, final GenerateValue<T> generateValue) {
        return getOrPutObject(tool, source, promptHash, 0, clazz, generateValue);
    }

    @Override
    public <T> CacheResult<List<T>> getOrPutList(final String tool, final String source, final String promptHash, final long ttlSeconds, final Class<T> clazz, final GenerateValue<List<T>> generateValue) {
        return getOrPutPrivate(tool, source, promptHash, ttlSeconds, generateValue, json -> jsonDeserializer.deserializeCollection(json, clazz));
    }

    @Override
    public <T, U> CacheResult<T> getOrPutGeneric(String tool, String source, String promptHash, long ttlSeconds, Class<T> container, Class<U> contained, GenerateValue<T> generateValue) {
        return getOrPutPrivate(tool, source, promptHash, ttlSeconds, generateValue, json -> jsonDeserializer.deserializeGeneric(json, container, contained));
    }

    @Override
    public <T, U> CacheResult<T> getOrPutGeneric(String tool, String source, String promptHash, Class<T> container, Class<U> contained, GenerateValue<T> generateValue) {
        return getOrPutGeneric(tool, source, promptHash, 0, container, contained, generateValue);
    }

    @Override
    public <T, U, V> CacheResult<T> getOrPutGeneric(String tool, String source, String promptHash, long ttlSeconds, Class<T> container, Class<U> contained, Class<V> contained2, GenerateValue<T> generateValue) {
        return getOrPutPrivate(tool, source, promptHash, ttlSeconds, generateValue, json -> jsonDeserializer.deserializeGeneric(json, container, contained, contained2));
    }

    @Override
    public <T, U, V> CacheResult<T> getOrPutGeneric(String tool, String source, String promptHash, Class<T> container, Class<U> contained, Class<V> contained2, GenerateValue<T> generateValue) {
        return getOrPutGeneric(tool, source, promptHash, 0, container, contained, contained2, generateValue);
    }

    /**
     * Wrap up the cache operation in a timed operation for logging.
     */
    private <T> CacheResult<T> getOrPutPrivate(final String tool, final String source, final String promptHash, final long ttlSeconds, final GenerateValue<T> generateValue, final Deserialize<T> deserializer) {
        return Try.withResources(() -> new TimedOperation("Cached object result for " + tool + " " + source))
                .of(t -> getOrPutTimed(tool, source, promptHash, ttlSeconds, generateValue, deserializer))
                .get();
    }

    /**
     * A generic method to get or put an object or list of objects in the cache.
     */
    @SuppressWarnings("NullAway")
    private <T> CacheResult<T> getOrPutTimed(final String tool, final String source, final String promptHash, final long ttlSeconds, final GenerateValue<T> generateValue, final Deserialize<T> deserializer) {
        if (localStorageCacheDisable.isDisabled() || container == null) {
            return new CacheResult<T>(generateValue.generate(), false);
        }

        logger.fine("Getting object from cache for tool " + tool + " source " + source + " prompt " + promptHash);

        return Try.of(() -> getString(tool, source, promptHash))
                .filter(result -> result != null && StringUtils.isNotBlank(result.result()))
                .onSuccess(v -> logger.fine("Cache hit for tool " + tool + " source " + source + " prompt " + promptHash))
                .mapTry(r -> new CacheResult<T>(deserializer.deserialize(r.result()), true))
                .onFailure(DeserializationFailed.class, ex -> logger.warning("Failed to deserialize cached object: " + exceptionHandler.getExceptionMessage(ex)))
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
    public <T> CacheResult<List<T>> getOrPutList(final String tool, final String source, final String promptHash, final Class<T> clazz, final GenerateValue<List<T>> generateValue) {
        return getOrPutList(tool, source, promptHash, 0, clazz, generateValue);
    }

    @Override
    public <T> CacheResult<T[]> getOrPutObjectArray(final String tool, final String source, final String promptHash, final long ttlSeconds, final Class<T> clazz, final Class<T[]> arrayClazz, final GenerateValue<T[]> generateValue) {
        return Try.withResources(() -> new TimedOperation("Cached array result for " + tool + " " + source))
                .of(t -> getOrPutObjectArrayTimed(tool, source, promptHash, ttlSeconds, clazz, arrayClazz, generateValue))
                .get();
    }

    @SuppressWarnings("NullAway")
    private <T> CacheResult<T[]> getOrPutObjectArrayTimed(final String tool, final String source, final String promptHash, final long ttlSeconds, final Class<T> clazz, final Class<T[]> arrayClazz, final GenerateValue<T[]> generateValue) {
        if (localStorageCacheDisable.isDisabled() || container == null) {
            return new CacheResult<T[]>(generateValue.generate(), false);
        }

        logger.fine("Getting object from cache for tool " + tool + " source " + source + " prompt " + promptHash);

        // Start by trying to load the full result from the local storage.
        // We assume local storage can save the complete array as a single item. Loading the full array is much more efficient.
        // The remote storage may not have this capability, so we fall back to loading each item individually.
        final Try<CacheResult<T[]>> localCacheTry = Try.of(() -> localStorageReadWrite.getString(tool, source, promptHash + "_all"))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .map(encryptor::decrypt)
                .map(this::decompressString)
                .map(result -> jsonDeserializer.deserialize(result, arrayClazz))
                .map(array -> new CacheResult<T[]>(array, true));

        if (localCacheTry.isSuccess()) {
            logger.fine("Local cache hit for tool " + tool + " source " + source + " prompt " + promptHash + " in local cache");
            return localCacheTry.get();
        }

        return Try.of(() -> getString(tool, source, promptHash))
                        .filter(result -> result != null && StringUtils.isNotBlank(result.result()))
                        .onSuccess(v -> logger.fine("Remote cache hit for tool " + tool + " source " + source + " prompt " + promptHash))
                        .mapTry(r -> NumberUtils.toInt(r.result(), 0))
                        // The cached result is the number of items in the array.
                        // We then loop over each index to get the individual items.
                        .map(count -> IntStream.range(0, count)
                                .boxed()
                                .collect(parallelToStream(index -> getString(tool, source, promptHash + "_" + index), sharedExecutor.getExecutor(), BATCH_SIZE))
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
                                }
                        )
                        .get();
    }

    private <T> void persistArrayResultLocal(final String tool, final String source, final String promptHash, final long ttlSeconds, final T[] value) {
        Try.of(() -> jsonDeserializer.serialize(value))
                .map(zipper::compressString)
                .map(encryptor::encrypt)
                .map(result -> localStorageReadWrite.putString(tool, source, promptHash + "_all", getTimestamp(ttlSeconds), result))
                .onFailure(ex -> logger.warning("Failed to persist full array result to local storage: " + exceptionHandler.getExceptionMessage(ex)));
    }

    @SuppressWarnings("ReturnValueIgnored")
    public <T> CacheResult<T[]> persistArrayResult(final String tool, final String source, final String promptHash, final long ttlSeconds, final GenerateValue<T[]> generateValue) {
        final T[] value = generateValue.generate();

        if (value != null) {
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
                                    sharedVirtualThreadExecutor.getExecutor(),
                                    BATCH_SIZE)
                    );
        }

        return new CacheResult<T[]>(value, false);
    }

    @SuppressWarnings("NullAway")
    @Override
    public void putString(final String tool, final String source, final String promptHash, final long ttlSeconds, final String value) {
        final Future<?> future = sharedVirtualThreadExecutor.getExecutor().submit(() -> putStringSync(tool, source, promptHash, ttlSeconds, value));
        pendingWrites.add(future);
        // Eagerly remove completed futures to avoid unbounded growth
        pendingWrites.removeIf(Future::isDone);
    }

    @SuppressWarnings("NullAway")
    private void putStringSync(final String tool, final String source, final String promptHash, final long ttlSeconds, final String value) {
        synchronized (CosmosLocalStorage.class) {
            if (localStorageCacheDisable.isDisabled() || localStorageCacheReadOnly.isReadOnly() || container == null) {
                return;
            }

            if (totalFailures.get() > MAX_FAILURES) {
                resetConnection();
            }


            final String redactedValue = Objects.requireNonNullElse(sanitizeDocument.sanitize(value), "");

            // If value exceeds SPLIT_ITEM_SIZE_BYTES, split into chunks and persist each separately
            final byte[] valueBytes = redactedValue.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (valueBytes.length > SPLIT_ITEM_SIZE_BYTES) {
                putStringChunked(tool, source, promptHash, ttlSeconds, valueBytes);
                return;
            }

            final Try<CosmosItemResponse<CacheItem>> result = Try.of(() -> zipper.compressString(redactedValue))
                    .map(encryptor::encrypt)
                    .map(encrypted -> localStorageReadWrite.putString(tool, source, promptHash, getTimestamp(ttlSeconds), encrypted))
                    .map(encrypted -> new CacheItem(
                            generateId(tool, source, promptHash),
                            tool,
                            source,
                            promptHash,
                            encrypted,
                            getTimestamp(ttlSeconds),
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
    }

    private void putStringChunked(final String tool, final String source, final String promptHash, final long ttlSeconds, final byte[] valueBytes) {
        final int totalChunks = (int) Math.ceil((double) valueBytes.length / SPLIT_ITEM_SIZE_BYTES);
        logger.fine("Splitting item of " + valueBytes.length + " bytes into " + totalChunks + " chunks for tool " + tool + " source " + source);
        // Save the total chunk count so reassembly can look it up directly
        putStringSync(tool, source, promptHash + "_chunked_size", ttlSeconds, String.valueOf(totalChunks));
        for (int chunkIndex = 0; chunkIndex < totalChunks; chunkIndex++) {
            final int start = chunkIndex * SPLIT_ITEM_SIZE_BYTES;
            final int end = Math.min(start + SPLIT_ITEM_SIZE_BYTES, valueBytes.length);
            final String chunk = new String(valueBytes, start, end - start, java.nio.charset.StandardCharsets.UTF_8);
            putStringSync(tool, source, promptHash + "_chunk_" + chunkIndex, ttlSeconds, chunk);
        }
    }

    private Integer sanitizeTtl(final long ttlSeconds) {
        if (ttlSeconds > 0) {
            return (int) ttlSeconds;
        }
        return -1;
    }

    @Nullable
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
