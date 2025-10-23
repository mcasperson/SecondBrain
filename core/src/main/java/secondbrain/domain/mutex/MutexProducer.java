package secondbrain.domain.mutex;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.injection.Preferred;

/**
 * Produces a LocalStorage instance based on the configuration.
 */
@ApplicationScoped
public class MutexProducer {

    @Inject
    @ConfigProperty(name = "sb.mutex.provider", defaultValue = "file")
    private String localStorageProvider;

    @Produces
    @Preferred
    @ApplicationScoped
    public Mutex produceLocalStorage(final FileLockMutex fileLockMutex, final CosmosMutex cosmosMutex) {
        if ("cosmos".equalsIgnoreCase(localStorageProvider)) {
            return cosmosMutex;
        }

        return fileLockMutex;
    }
}
