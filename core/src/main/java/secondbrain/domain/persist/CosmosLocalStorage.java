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
import reactor.core.Exceptions;
import secondbrain.domain.concurrency.SharedVirtualThreadExecutor;
import secondbrain.domain.encryption.Encryptor;
import secondbrain.domain.exceptionhandling.ExceptionHandler;
import secondbrain.domain.exceptions.*;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.json.JsonDeserializer;
import secondbrain.domain.persist.config.*;
import secondbrain.domain.sanitize.SanitizeDocument;
import secondbrain.domain.zip.Zipper;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.stream.IntStream;

import static com.pivovarit.collectors.ParallelCollectors.Batching.parallelToStream;

/**
 * Azure Cosmos DB implementation of LocalStorage for caching API calls and LLM results.
 * This implementation uses a local file cache to potentially speed up reads and reduce Cosmos DB read costs.
 */
@ApplicationScoped
public class CosmosLocalStorage implements LocalStorage {

    private static final int FLUSH_WAIT_MINUTES = 10;
    private static final int BATCH_SIZE = 5;
    private static final String CONTAINER_NAME = "localstoragezipped";
    private static final String DATABASE_NAME = "secondbrain";
    private static final int DEFAULT_TTL_SECONDS = 86400;
    private static final int MAX_FAILURES = 5; // 2 MB
    private static final int SPLIT_ITEM_SIZE_BYTES = 1024 * 1024;
    private static final int LARGE_OBJECT_WARNING_BYTES = 2 * 1024 * 1024;
    private static final int TTL_NO_EXPIRE = -1;

    private final AtomicInteger totalReads = new AtomicInteger();
    private final AtomicInteger totalCacheHits = new AtomicInteger();
    private final AtomicInteger totalFailures = new AtomicInteger();
    private final List<Future<?>> pendingWrites = Collections.synchronizedList(new ArrayList<>());

    @Inject
    private LocalStorageDisableTool localStorageDisableTool;

    @Inject
    private LocalStorageWriteOnlyTool localStorageWriteOnlyTool;

    @Inject
    private LocalStorageReadOnlyTool localStorageReadOnlyTool;

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
    @Preferred
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

    // This observer forces the container to instantiate the bean at startup
    public void onStartup(@Observes final Startup event) {
        // Initialization logic here
    }

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
        final CompletableFuture<?>[] waitFutures = toWait.stream()
                .map(f -> CompletableFuture.runAsync(
                        () -> Try.run(() -> f.get(FLUSH_WAIT_MINUTES, TimeUnit.MINUTES))
                                .onFailure(ex -> logger.warning("Error waiting for pending write: " + exceptionHandler.getExceptionMessage(ex))),
                        sharedVirtualThreadExecutor.getExecutor()))
                .toArray(CompletableFuture[]::new);
        Try.run(() -> CompletableFuture.allOf(waitFutures).get(FLUSH_WAIT_MINUTES, TimeUnit.MINUTES))
                .onFailure(ex -> logger.warning("Timed out or failed waiting for all pending writes: " + exceptionHandler.getExceptionMessage(ex)));
    }

    private void resetConnection() {
        synchronized (CosmosLocalStorage.class) {
            logger.warning("Resetting Cosmos DB connection after " + totalFailures.get() + " errors");
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
                .connectionSharingAcrossClientsEnabled(true)
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
            if (localStorageCacheDisable.isDisabled() || localStorageDisableTool.isToolDisabled(tool) || localStorageCacheWriteOnly.isWriteOnly() || localStorageWriteOnlyTool.isToolWriteOnly(tool) || container == null) {
                return new CacheResult<String>(null, null, false);
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
                    .map(cache -> new CacheResult<String>(cache.get(), null, true))
                    // If there was no locally cached value, get from Cosmos DB
                    .recover(NoSuchElementException.class, ex -> loadFromDatabase(tool, source, promptHash))
                    // Decrypt and decompress the result if it was from cache
                    .map(value -> unpack(value, tool, source))
                    // If the item is not found, return a CacheResult with null
                    .recoverWith(Exception.class, this::handleError)
                    // Track failures
                    .onFailure(ex -> totalFailures.incrementAndGet())
                    // Log errors
                    .onFailure(ex -> logger.warning("Failed to get string: " + exceptionHandler.getDetailedExceptionMessage(ex)));

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

    private Try<CacheResult<String>> handleError(final Exception ex) {
        if (ex instanceof CosmosException clientEx) {
            if (clientEx.getStatusCode() == 404) {
                return Try.of(() -> new CacheResult<String>(null, null, false));
            }

            /*
                This is the error when the partition key size is reached:

                {"ClassName":"ForbiddenException","userAgent":"azsdk-java-cosmos/4.74.0 Linux/7.0.9-ogc3.2.fc44.x86_64 JRE/25.0.3","statusCode":403,"resourceAddress":"rntbd://cdb-ms-prod-australiaeast1-be202.documents.azure.com:15974/apps/0d6f3af4-c6cf-44b1-9042-8b539a8cf34b/services/d613794b-c903-4553-ae07-a42e2a28896a/partitions/14015e47-707f-4e9d-8a5a-a7924fdc5f56/replicas/134253925411391521p/","error":"{\"Errors\":[\"Partition key reached maximum size of 20 GB. Learn more: https://aka.ms/CosmosDB/sql/errors/full-pk\"]}","innerErrorMessage":"[\"Partition key reached maximum size of 20 GB. Learn more: https://aka.ms/CosmosDB/sql/errors/full-pk\"]","causeInfo":null,"responseHeaders":"{x-ms-current-replica-set-size=4, x-ms-last-state-change-utc=Sun, 21 Jun 2026 08:01:37.294 GMT, x-ms-request-duration-ms=0.752, x-ms-session-token=0:-1#17700919, lsn=17700919, x-ms-request-charge=1.24, x-ms-schemaversion=1.21, x-ms-transport-request-id=45, x-ms-number-of-read-regions=0, x-ms-current-write-quorum=3, x-ms-cosmos-quorum-acked-llsn=17700919, x-ms-quorum-acked-lsn=17700919, x-ms-activity-id=b6cbc0ae-f816-4b15-8c1d-30f14dbb15bc, x-ms-xp-role=1, x-ms-global-Committed-lsn=17700919, x-ms-cosmos-llsn=17700919, x-ms-serviceversion= version=2.14.0.0, x-ms-substatus=1014}","cosmosDiagnostics":{"userAgent":"azsdk-java-cosmos/4.74.0 Linux/7.0.9-ogc3.2.fc44.x86_64 JRE/25.0.3","activityId":"b6cbc0ae-f816-4b15-8c1d-30f14dbb15bc","requestLatencyInMs":28,"requestStartTimeUTC":"2026-06-21T21:35:34.818017073Z","requestEndTimeUTC":"2026-06-21T21:35:34.846326474Z","responseStatisticsList":[{"storeResult":{"storePhysicalAddress":"rntbd://cdb-ms-prod-australiaeast1-be202.documents.azure.com:15974/apps/0d6f3af4-c6cf-44b1-9042-8b539a8cf34b/services/d613794b-c903-4553-ae07-a42e2a28896a/partitions/14015e47-707f-4e9d-8a5a-a7924fdc5f56/replicas/134253925411391521p/","lsn":17700919,"quorumAckedLSN":17700919,"currentReplicaSetSize":3,"globalCommittedLsn":17700919,"partitionKeyRangeId":"0","isValid":true,"statusCode":403,"subStatusCode":1014,"isGone":false,"isNotFound":false,"isInvalidPartition":false,"isThroughputControlRequestRateTooLarge":false,"requestCharge":1.24,"itemLSN":-1,"sessionToken":"0:-1#17700919","backendLatencyInMs":0.752,"retryAfterInMs":null,"exceptionMessage":"[\"Partition key reached maximum size of 20 GB. Learn more: https://aka.ms/CosmosDB/sql/errors/full-pk\"]","exceptionResponseHeaders":"{x-ms-current-replica-set-size=4, x-ms-last-state-change-utc=Sun, 21 Jun 2026 08:01:37.294 GMT, x-ms-request-duration-ms=0.752, x-ms-session-token=0:-1#17700919, lsn=17700919, x-ms-request-charge=1.24, x-ms-schemaversion=1.21, x-ms-transport-request-id=45, x-ms-number-of-read-regions=0, x-ms-current-write-quorum=3, x-ms-cosmos-quorum-acked-llsn=17700919, x-ms-quorum-acked-lsn=17700919, x-ms-activity-id=b6cbc0ae-f816-4b15-8c1d-30f14dbb15bc, x-ms-xp-role=1, x-ms-global-Committed-lsn=17700919, x-ms-cosmos-llsn=17700919, x-ms-documentdb-partitionkeyrangeid=0, x-ms-serviceversion= version=2.14.0.0, x-ms-substatus=1014}","replicaStatusList":{"Ignoring":["15928:S:Connected","16024:S:Connected","15375:S:Connected"],"Attempting":["15974:P:Connected"]},"transportRequestTimeline":[{"eventName":"created","startTimeUTC":"2026-06-21T21:35:34.818855600Z","durationInMilliSecs":0.031379},{"eventName":"queued","startTimeUTC":"2026-06-21T21:35:34.818886979Z","durationInMilliSecs":2.5E-4},{"eventName":"channelAcquisitionStarted","startTimeUTC":"2026-06-21T21:35:34.818887229Z","durationInMilliSecs":0.188796},{"eventName":"pipelined","startTimeUTC":"2026-06-21T21:35:34.819076025Z","durationInMilliSecs":6.955914},{"eventName":"transitTime","startTimeUTC":"2026-06-21T21:35:34.826031939Z","durationInMilliSecs":19.514448},{"eventName":"decodeTime","startTimeUTC":"2026-06-21T21:35:34.845546387Z","durationInMilliSecs":0.080391},{"eventName":"received","startTimeUTC":"2026-06-21T21:35:34.845626778Z","durationInMilliSecs":0.549684},{"eventName":"completed","startTimeUTC":"2026-06-21T21:35:34.846176462Z","durationInMilliSecs":0.007925}],"rntbdRequestLengthInBytes":818,"rntbdResponseLengthInBytes":338,"requestPayloadLengthInBytes":369,"responsePayloadLengthInBytes":338,"channelStatistics":{"channelId":"f0ce498b","channelTaskQueueSize":0,"pendingRequestsCount":0,"lastReadTime":"2026-06-21T21:35:34.223841238Z","waitForConnectionInit":false},"serviceEndpointStatistics":{"availableChannels":1,"acquiredChannels":0,"executorTaskQueueSize":0,"inflightRequests":1,"lastSuccessfulRequestTime":"2026-06-21T21:35:34.224Z","lastRequestTime":"2026-06-21T21:35:34.167Z","createdTime":"2026-06-21T21:35:32.141194313Z","isClosed":false,"cerMetrics":{}}},"requestResponseTimeUTC":"2026-06-21T21:35:34.846326474Z","requestStartTimeUTC":"2026-06-21T21:35:34.818855600Z","requestResourceType":"Document","requestOperationType":"Upsert","requestSessionToken":null,"e2ePolicyCfg":null,"excludedRegions":null,"sessionTokenEvaluationResults":[],"perPartitionCircuitBreakerInfoHolder":null,"perPartitionFailoverInfoHolder":null}],"supplementalResponseStatisticsList":[],"addressResolutionStatistics":{},"regionsContacted":["australia east"],"retryContext":{"statusAndSubStatusCodes":null,"retryLatency":0,"retryCount":0},"metadataDiagnosticsContext":{"metadataDiagnosticList":null,"empty":true},"serializationDiagnosticsContext":{"serializationDiagnosticsList":null},"gatewayStatisticsList":[],"samplingRateSnapshot":0.0,"bloomFilterInsertionCountSnapshot":0,"systemInformation":{"usedMemory":"82439 KB","availableMemory":"31342073 KB","systemCpuLoad":"(2026-06-21T21:35:30.142073684Z 20.0%)","availableProcessors":24},"clientCfgs":{"id":1,"machineId":"uuid:52dd1f0e-f848-4d6b-ab34-4251001a89c9","connectionMode":"DIRECT","numberOfClients":2,"isPpafEnabled":"","isFalseProgSessionTokenMergeEnabled":"true","excrgns":"[]","clientEndpoints":{"https://aiofsauron.documents.azure.com:443/":2},"connCfg":{"rntbd":"(cto:PT5S, nrto:PT5S, icto:PT0S, ieto:PT1H, mcpe:130, mrpc:30, cer:true)","gw":"(cps:1000, nrto:PT1M, icto:PT1M, cto:PT45S, p:false, http2:(enabled:false, maxc:1000, minc:24, maxs:30))","other":"(ed: true, cs: true, rv: true)"},"consistencyCfg":"(consistency: Session, readConsistencyStrategy: null,  mm: true, prgns: [])","proactiveInitCfg":"","e2ePolicyCfg":"","sessionRetryCfg":"","partitionLevelCircuitBreakerCfg":"(cb: false, type: CONSECUTIVE_EXCEPTION_COUNT_BASED, rexcntt: 10, wexcntt: 5)"}}}
             */
            if (clientEx.getStatusCode() == 403 && clientEx.getMessage().contains("Partition key reached maximum size")) {
                logger.severe("Cosmos partition key size limit reached");
                return Try.of(() -> new CacheResult<String>(null, null, false));
            }
        }

        /*
        Catch this exception:
        reactor.core.Exceptions$ReactiveException: java.lang.InterruptedException
            at reactor.core.Exceptions.propagate(Exceptions.java:410)
            at reactor.core.publisher.BlockingSingleSubscriber.blockingGet(BlockingSingleSubscriber.java:96)
            at reactor.core.publisher.Mono.block(Mono.java:1779)
            at com.azure.cosmos.CosmosContainer.blockItemResponse(CosmosContainer.java:271)
            at com.azure.cosmos.CosmosContainer.readItem(CosmosContainer.java:629)
         */
        if (ex instanceof InterruptedException || Exceptions.unwrap(ex) instanceof InterruptedException) {
            return Try.of(() -> new CacheResult<String>(null, null, false));
        }

        return Try.failure(ex);
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
                .map(cache -> new CacheResult<String>(cache.get(), null, true))
                .recover(NoSuchElementException.class, ex -> loadFromDatabase(tool, source, promptHash + "_chunked_size"))
                .map(value -> unpack(value, tool, source))
                .recoverWith(CosmosException.class, this::handleError)
                .getOrNull();

        if (sizeResult == null || StringUtils.isBlank(sizeResult.result())) {
            return new CacheResult<String>(null, null, false);
        }

        final int total = NumberUtils.toInt(sizeResult.result(), 0);
        if (total <= 0) {
            return new CacheResult<String>(null, null, false);
        }

        if (total > 2) {
            logger.warning("Reassembling " + total + " chunks for tool " + tool + " source " + source + " prompt " + promptHash + ". Consider reducing the size of cached objects.");
        }

        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < total; i++) {
            final String key = promptHash + "_chunk_" + i;
            final CacheResult<String> chunk = Try
                    .of(() -> localStorageReadWrite.getString(tool, source, key))
                    .filter(Optional::isPresent)
                    .map(cache -> new CacheResult<String>(cache.get(), null, true))
                    .recover(NoSuchElementException.class, ex -> loadFromDatabase(tool, source, key))
                    .map(value -> unpack(value, tool, source))
                    .recoverWith(CosmosException.class, this::handleError)
                    .getOrNull();

            if (chunk == null || StringUtils.isBlank(chunk.result())) {
                logger.warning("Missing chunk " + i + " of " + total + " for tool " + tool + " source " + source + " prompt " + promptHash);
                return new CacheResult<String>(null, null, false);
            }
            sb.append(chunk.result());
        }

        logger.fine("Reassembled " + total + " chunks for tool " + tool + " source " + source + " prompt " + promptHash);
        return new CacheResult<>(sb.toString(), null, true);
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


        return new CacheResult<String>(original, null, true);
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

    @SuppressWarnings("NullAway")
    private CacheResult<String> loadFromDatabaseTimed(final String tool, final String source, final String promptHash) {
        if (container == null) {
            throw new LocalStorageFailure("Cosmos DB container is not initialized");
        }

        final String id = generateId(tool, source, promptHash);
        final PartitionKey partitionKey = new PartitionKey(tool);

        final CosmosItemResponse<CacheItem> response = Try.of(() -> container.readItem(
                        id,
                        partitionKey,
                        CacheItem.class
                ))
                .onFailure(ex -> {
                    if (ex instanceof CosmosException && ((CosmosException) ex).getStatusCode() == 404) {
                        logger.fine("Failed to read item from container: " + exceptionHandler.getExceptionMessage(ex));
                    } else {
                        logger.warning("Failed to read item from container: " + exceptionHandler.getExceptionMessage(ex));
                    }
                })
                .get();

        // Check if item has expired (if timestamp is set)
        if (response.getItem().timestamp != null) {
            if (response.getItem().timestamp < Instant.now().getEpochSecond()) {
                return new CacheResult<String>(null, null, false);
            }
        }

        // If we are loading this from the remote cache, save it locally too
        localStorageReadWrite.putString(tool, source, promptHash, response.getItem().timestamp, response.getItem().response());

        return new CacheResult<String>(response.getItem().response(), null, true);
    }

    @Override
    public CacheResult<String> getOrPutString(final String tool, final String source, final String promptHash, final long ttlSeconds, final GenerateValue<String> generateValue) {
        return Try.withResources(() -> new TimedOperation("Cached string result for " + tool + " " + source))
                .of(t -> getOrPutStringTimed(tool, source, promptHash, ttlSeconds, generateValue))
                .get();
    }

    private CacheResult<String> getOrPutStringTimed(final String tool, final String source, final String promptHash, final long ttlSeconds, final GenerateValue<String> generateValue) {
        if (localStorageCacheDisable.isDisabled() || container == null) {
            return new CacheResult<String>(generateValue.generate(), null, false);
        }

        logger.fine("Getting string from cache for tool " + tool + " source " + source + " prompt " + promptHash);

        return Try
                .of(() -> getString(tool, source, promptHash))
                .filter(result -> result != null && StringUtils.isNotBlank(result.result()))
                .onSuccess(v -> logger.fine("Cache hit for tool " + tool + " source " + source + " prompt " + promptHash))
                .recover(result -> {
                    logger.fine("Cache lookup missed for tool " + tool + " source " + source + " prompt " + promptHash);
                    final CacheResult<String> value = Try.of(generateValue::generate)
                            .map(v -> new CacheResult<String>(v, null, false))
                            .recover(TimeoutException.class, ex -> {
                                logger.fine("Timeout when generating value from " + tool + ", returning null");
                                return new CacheResult<String>(null, ex, false);
                            })
                            .recover(ex -> {
                                logger.warning("Unexpected error when generating value for tool " + tool + ": " + exceptionHandler.getExceptionMessage(ex));
                                return new CacheResult<String>(null, ex, false);
                            })
                            .get();

                    if (StringUtils.isNotBlank(value.result())) {
                        putString(tool, source, promptHash, ttlSeconds, value.result());
                    }

                    return value;
                })
                .onFailure(LocalStorageFailure.class, ex -> logger.warning("Failed to generate value or save it to the database: " + exceptionHandler.getExceptionMessage(ex)))
                .recover(LocalStorageFailure.class, ex -> {
                    logger.fine("Cache lookup missed for tool " + tool + " source " + source + " prompt " + promptHash);
                    return Try.of(generateValue::generate)
                            .map(v -> new CacheResult<String>(v, null, false))
                            .recover(TimeoutException.class, ex2 -> {
                                logger.fine("Timeout when generating value from " + tool + ", returning null");
                                return new CacheResult<String>(null, ex2, false);
                            })
                            .recover(ex2 -> {
                                logger.warning("Unexpected error when generating value for tool " + tool + ": " + exceptionHandler.getExceptionMessage(ex2));
                                return new CacheResult<String>(null, ex2, false);
                            })
                            .get();
                })
                .get();
    }

    @Override
    public CacheResult<String> getOrPutString(final String tool, final String source, final String promptHash, final GenerateValue<String> generateValue) {
        return getOrPutString(tool, source, promptHash, TTL_NO_EXPIRE, generateValue);
    }

    @Override
    public <T> CacheResult<T> getOrPutObject(final String tool, final String source, final String promptHash, final long ttlSeconds, final Class<T> clazz, final GenerateValue<T> generateValue) {
        return getOrPutPrivate(tool, source, promptHash, ttlSeconds, generateValue, json -> jsonDeserializer.deserialize(json, clazz));
    }

    @Override
    public <T> CacheResult<T> getOrPutObject(final String tool, final String source, final String promptHash, final Class<T> clazz, final GenerateValue<T> generateValue) {
        return getOrPutObject(tool, source, promptHash, TTL_NO_EXPIRE, clazz, generateValue);
    }

    @Override
    public <T> CacheResult<List<T>> getOrPutList(final String tool, final String source, final String promptHash, final long ttlSeconds, final Class<T> clazz, final GenerateValue<List<T>> generateValue) {
        return getOrPutPrivate(tool, source, promptHash, ttlSeconds, generateValue, json -> jsonDeserializer.deserializeCollection(json, clazz));
    }

    @Override
    public <T, U> CacheResult<T> getOrPutGeneric(final String tool, final String source, final String promptHash, final long ttlSeconds, final Class<T> container, final Class<U> contained, final GenerateValue<T> generateValue) {
        return getOrPutPrivate(tool, source, promptHash, ttlSeconds, generateValue, json -> jsonDeserializer.deserializeGeneric(json, container, contained));
    }

    @Override
    public <T, U> CacheResult<T> getOrPutGeneric(String tool, String source, String promptHash, Class<T> container, Class<U> contained, GenerateValue<T> generateValue) {
        return getOrPutGeneric(tool, source, promptHash, TTL_NO_EXPIRE, container, contained, generateValue);
    }

    @Override
    public <T, U, V> CacheResult<T> getOrPutGeneric(final String tool, final String source, final String promptHash, final long ttlSeconds, final Class<T> container, final Class<U> contained, final Class<V> contained2, final GenerateValue<T> generateValue) {
        return getOrPutPrivate(tool, source, promptHash, ttlSeconds, generateValue, json -> jsonDeserializer.deserializeGeneric(json, container, contained, contained2));
    }

    @Override
    public <T, U, V> CacheResult<T> getOrPutGeneric(final String tool, final String source, final String promptHash, final Class<T> container, final Class<U> contained, final Class<V> contained2, GenerateValue<T> generateValue) {
        return getOrPutGeneric(tool, source, promptHash, TTL_NO_EXPIRE, container, contained, contained2, generateValue);
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
            return new CacheResult<T>(generateValue.generate(), null, false);
        }

        logger.fine("Getting object from cache for tool " + tool + " source " + source + " prompt " + promptHash);

        return Try.of(() -> getString(tool, source, promptHash))
                .filter(result -> result != null && StringUtils.isNotBlank(result.result()))
                .onSuccess(v -> logger.fine("Cache hit for tool " + tool + " source " + source + " prompt " + promptHash))
                .peek(r -> {
                    final int size = r.result().length() * 2; // approximate byte size for UTF-16 chars
                    if (size > LARGE_OBJECT_WARNING_BYTES) {
                        logger.warning("Large cached object loaded (" + (size / 1024 / 1024) + " MB) for tool " + tool + " source " + source + " prompt " + promptHash);
                    }
                })
                .mapTry(r -> new CacheResult<T>(deserializer.deserialize(r.result()), null, true))
                .onFailure(DeserializationFailed.class, ex -> logger.warning("Failed to deserialize cached object for tool " + tool + " source " + source + " prompt " + promptHash + ": " + exceptionHandler.getExceptionMessage(ex)))
                .recoverWith(ex -> Try.of(() -> {
                            logger.fine("Cache lookup missed for tool " + tool + " source " + source + " prompt " + promptHash);
                            logger.fine("Exception: " + exceptionHandler.getExceptionMessage(ex));
                            final CacheResult<T> value = Try.of(generateValue::generate)
                                    .map(v -> new CacheResult<T>(v, null, false))
                                    .recover(TimeoutException.class, ex2 -> {
                                        logger.fine("Timeout when generating value from " + tool + ", returning null");
                                        return new CacheResult<T>(null, ex2, false);
                                    })
                                    .recover(ex2 -> {
                                        logger.warning("Unexpected error when generating value for tool " + tool + ": " + exceptionHandler.getExceptionMessage(ex2));
                                        return new CacheResult<T>(null, ex2, false);
                                    })
                                    .get();

                            if (value.result() != null) {
                                putString(
                                        tool,
                                        source,
                                        promptHash,
                                        ttlSeconds,
                                        jsonDeserializer.serialize(value.result()));
                            }
                            return value;
                        })
                )
                .onFailure(LocalStorageFailure.class, ex -> logger.warning(exceptionHandler.getExceptionMessage(ex)))
                .onFailure(SerializationFailed.class, ex -> logger.warning(exceptionHandler.getExceptionMessage(ex)))
                .recover(LocalStorageFailure.class, ex -> {
                    logger.fine("Cache lookup missed for tool " + tool + " source " + source + " prompt " + promptHash);
                    return Try.of(generateValue::generate)
                            .map(v -> new CacheResult<T>(v, null, false))
                            .recover(TimeoutException.class, ex2 -> {
                                logger.fine("Timeout when generating value from " + tool + ", returning null");
                                return new CacheResult<T>(null, ex2, false);
                            })
                            .recover(ex2 -> {
                                logger.warning("Unexpected error when generating value for tool " + tool + ": " + exceptionHandler.getExceptionMessage(ex2));
                                return new CacheResult<T>(null, ex2, false);
                            })
                            .get();
                })
                // Gracefully deal with an object that can't be serialized
                .recover(SerializationFailed.class, ex -> Try.of(generateValue::generate)
                        .map(v -> new CacheResult<T>(v, null, false))
                        .recover(TimeoutException.class, ex2 -> {
                            logger.fine("Timeout when generating value from " + tool + ", returning null");
                            return new CacheResult<T>(null, ex2, false);
                        })
                        .recover(ex2 -> {
                            logger.warning("Unexpected error when generating value for tool " + tool + ": " + exceptionHandler.getExceptionMessage(ex2));
                            return new CacheResult<T>(null, ex2, false);
                        })
                        .get())
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
            return new CacheResult<T[]>(generateValue.generate(), null, false);
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
                .map(array -> new CacheResult<T[]>(array, null, true));

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
                .map(array -> new CacheResult<T[]>(array, null, true))
                .recoverWith(ex -> Try.of(() -> {
                            logger.fine("Cache lookup missed for tool " + tool + " source " + source + " prompt " + promptHash);
                            logger.fine("Exception: " + exceptionHandler.getExceptionMessage(ex));
                            return persistArrayResult(tool, source, promptHash, ttlSeconds, generateValue);
                        })
                )
                .onFailure(LocalStorageFailure.class, ex -> logger.warning(exceptionHandler.getExceptionMessage(ex)))
                .recover(LocalStorageFailure.class, ex -> {
                            logger.fine("Cache lookup missed for tool " + tool + " source " + source + " prompt " + promptHash);
                            return Try.of(generateValue::generate)
                                    .map(v -> new CacheResult<T[]>(v, null, false))
                                    .recover(TimeoutException.class, ex2 -> {
                                        logger.fine("Timeout when generating value from " + tool + ", returning null");
                                        return new CacheResult<T[]>(null, ex2, false);
                                    })
                                    .recover(ex2 -> {
                                        logger.warning("Unexpected error when generating value for tool " + tool + ": " + exceptionHandler.getExceptionMessage(ex2));
                                        return new CacheResult<T[]>(null, ex2, false);
                                    })
                                    .get();
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
        final CacheResult<T[]> value = Try.of(generateValue::generate)
                .map(v -> new CacheResult<T[]>(v, null, false))
                .recover(TimeoutException.class, ex -> {
                    logger.fine("Timeout when generating value from " + tool + ", returning null");
                    return new CacheResult<T[]>(null, ex, false);
                })
                .recover(ex -> {
                    logger.warning("Unexpected error when generating value for tool " + tool + ": " + exceptionHandler.getExceptionMessage(ex));
                    return new CacheResult<T[]>(null, ex, false);
                })
                .get();

        if (value.result() != null) {
            // Persist the full array as a single compressed and encrypted item in local storage
            persistArrayResultLocal(tool, source, promptHash, ttlSeconds, value.result());

            // The result associated with the original hash is the count of items
            putString(
                    tool,
                    source,
                    promptHash,
                    ttlSeconds,
                    value.result().length + "");

            // Serialize and persist each item sequentially to reduce peak memory.
            // Parallel writes would hold multiple large serialized strings in flight
            // simultaneously (each item can be ~1MB), risking OOM under load.
            for (int index = 0; index < value.result().length; index++) {
                putString(
                        tool,
                        source,
                        promptHash + "_" + index,
                        ttlSeconds,
                        jsonDeserializer.serialize(value.result()[index]));
            }
        }

        return value;
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
            if (localStorageCacheDisable.isDisabled() || localStorageDisableTool.isToolDisabled(tool) || localStorageCacheReadOnly.isReadOnly() || localStorageReadOnlyTool.isToolReadOnly(tool) || container == null) {
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
                    .onFailure(ex -> logger.warning("Failed to put string: " + exceptionHandler.getExceptionMessage(ex)));

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
