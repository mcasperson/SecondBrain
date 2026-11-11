package secondbrain.domain.persist;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Startup;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Creates {@link CosmosLocalStorage} at startup so connection problems surface early, but only when Cosmos is the
 * selected local storage provider. Otherwise, deployments using h2 would needlessly try to connect to Cosmos.
 */
@ApplicationScoped
public class CosmosLocalStorageStartup {
    @Inject
    @ConfigProperty(name = "sb.localstorage.provider", defaultValue = "h2")
    private String localStorageProvider;

    @Inject
    private Instance<CosmosLocalStorage> cosmosLocalStorage;

    public void onStartup(@Observes final Startup event) {
        if ("cosmos".equalsIgnoreCase(localStorageProvider)) {
            // Calling a method on the client proxy forces the bean to be created
            cosmosLocalStorage.get().flush();
        }
    }
}
