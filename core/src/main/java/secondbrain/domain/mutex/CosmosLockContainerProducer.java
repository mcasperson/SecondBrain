package secondbrain.domain.mutex;

import com.azure.core.util.MetricsOptions;
import com.azure.cosmos.*;
import com.azure.cosmos.models.CosmosClientTelemetryConfig;
import com.azure.cosmos.models.CosmosContainerProperties;
import com.azure.cosmos.models.CosmosMicrometerMetricsOptions;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.exceptionhandling.ExceptionHandler;
import secondbrain.domain.exceptions.LocalStorageFailure;

import java.util.Optional;
import java.util.logging.Logger;

/**
 * A factory that creates the Cosmos DB container used by {@link CosmosMutex}.
 * Tests replace this class with their own producer method that returns a test instance of CosmosLockContainer.
 */
@ApplicationScoped
public class CosmosLockContainerProducer {
    private static final String DATABASE_NAME = "secondbrainlock";
    private static final String CONTAINER_NAME = "locks";
    private static final String LOCK_PARTITION_KEY = "/lock";

    @Inject
    @ConfigProperty(name = "sb.cosmos.endpoint")
    private Optional<String> cosmosEndpoint;

    @Inject
    @ConfigProperty(name = "sb.cosmos.key")
    private Optional<String> cosmosKey;

    /**
     * The default TTL represents how long a lock can be held. Either the lock becomes stale,
     * or Cosmos DB automatically deletes it.
     */
    @Inject
    @ConfigProperty(name = "sb.cosmos.lockttl", defaultValue = CosmosMutex.DEFAULT_TTL + "")
    private Optional<String> lockTtl;

    @Inject
    @ConfigProperty(name = "sb.cosmos.lockdatabase", defaultValue = DATABASE_NAME)
    private Optional<String> databaseName;

    @Inject
    @ConfigProperty(name = "sb.cosmos.lockscontainer", defaultValue = CONTAINER_NAME)
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
    private ExceptionHandler exceptionHandler;

    @Inject
    private Logger logger;

    @Produces
    public CosmosLockContainer produceCosmosLockContainer() {
        if (cosmosEndpoint.isEmpty() || cosmosKey.isEmpty()) {
            throw new LocalStorageFailure("Cosmos DB endpoint and key must be configured");
        }

        final String databaseName = this.databaseName.orElse(DATABASE_NAME);
        final String containerName = this.containerName.orElse(CONTAINER_NAME);

        final MetricsOptions metricsOptions = new CosmosMicrometerMetricsOptions();
        metricsOptions.setEnabled(false);

        final CosmosClientTelemetryConfig telemetryOptions = new CosmosClientTelemetryConfig();
        telemetryOptions.metricsOptions(metricsOptions);

        final CosmosClientBuilder builder = new CosmosClientBuilder()
                .endpoint(cosmosEndpoint.get())
                .key(cosmosKey.get())
                .consistencyLevel(ConsistencyLevel.SESSION)
                .clientTelemetryConfig(telemetryOptions)
                .endpointDiscoveryEnabled(autoDiscovery)
                .connectionSharingAcrossClientsEnabled(true)
                .contentResponseOnWriteEnabled(false);

        final CosmosClient cosmosClient = (gatewayMode ? builder.gatewayMode() : builder)
                .buildClient();

        cosmosClient.createDatabaseIfNotExists(databaseName);

        final CosmosDatabase database = cosmosClient.getDatabase(databaseName);

        // Create container if it doesn't exist
        final CosmosContainerProperties containerProperties = new CosmosContainerProperties(
                containerName,
                LOCK_PARTITION_KEY
        );

        // Set TTL on the container to enable automatic deletion of expired items
        containerProperties.setDefaultTimeToLiveInSeconds(CosmosMutex.parseLockTtlSeconds(lockTtl));

        Try.of(() -> database.createContainerIfNotExists(containerProperties))
                .onFailure(ex -> logger.warning("Failed to create container: " + exceptionHandler.getExceptionMessage(ex)));

        return new CosmosLockContainerLive(cosmosClient, database.getContainer(containerName));
    }

    public void disposeCosmosLockContainer(@Disposes final CosmosLockContainer container) {
        Try.run(container::close)
                .onFailure(ex -> logger.warning(exceptionHandler.getExceptionMessage(ex)));
    }
}
