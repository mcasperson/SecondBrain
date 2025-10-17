package secondbrain.domain.persist;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.injection.Preferred;

/**
 * Produces a LocalStorage instance based on the configuration.
 */
@ApplicationScoped
public class LocalStorageProducer {

    @Inject
    @ConfigProperty(name = "sb.localstorage.provider", defaultValue = "h2")
    private String localStorageProvider;

    @Produces
    @Preferred
    @ApplicationScoped
    public LocalStorage produceLocalStorage(final H2LocalStorage h2LocalStorage, final CosmosLocalStorage cosmosLocalStorage) {
        if ("cosmos".equalsIgnoreCase(localStorageProvider)) {
            return cosmosLocalStorage;
        }

        return h2LocalStorage;
    }
}
