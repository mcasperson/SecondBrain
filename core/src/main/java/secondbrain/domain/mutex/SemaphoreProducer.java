package secondbrain.domain.mutex;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.injection.Preferred;

/**
 * Produces a Semaphore instance based on the configuration.
 */
@ApplicationScoped
public class SemaphoreProducer {

    @Inject
    @ConfigProperty(name = "sb.mutex.provider", defaultValue = "file")
    private String localStorageProvider;

    @Produces
    @Preferred
    @ApplicationScoped
    public Semaphore produceSemaphore(final FileLockSemaphore fileLockSemaphore, final CosmosSemaphore cosmosSemaphore) {
        if ("cosmos".equalsIgnoreCase(localStorageProvider)) {
            return cosmosSemaphore;
        }

        return fileLockSemaphore;
    }
}

