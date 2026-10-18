package secondbrain.domain.persist;

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
 * A factory that creates the Cosmos DB container used by {@link CosmosLocalStorage}.
 * The container is a dependent bean, so each lookup creates a new connection, which allows
 * CosmosLocalStorage to reconnect after repeated failures. Tests replace this class with their
 * own producer method that returns a test instance of CosmosCacheContainer.
 */
@ApplicationScoped
public class CosmosCacheContainerProducer {
    private static final String CONTAINER_NAME = "localstoragezipped";
    private static final String DATABASE_NAME = "secondbrain";
    private static final int DEFAULT_TTL_SECONDS = 86400;

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
    private ExceptionHandler exceptionHandler;

    @Inject
    private Logger logger;

    @Produces
    public CosmosCacheContainer produceCosmosCacheContainer() {
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

        final CosmosClient cosmosClient = (gatewayMode ? builder.gatewayMode() : builder)
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

        return new CosmosCacheContainerLive(cosmosClient, database.getContainer(containerName.orElse(CONTAINER_NAME)));
    }

    public void disposeCosmosCacheContainer(@Disposes final CosmosCacheContainer container) {
        Try.run(container::close)
                .onFailure(ex -> logger.warning(exceptionHandler.getExceptionMessage(ex)));
    }
}
