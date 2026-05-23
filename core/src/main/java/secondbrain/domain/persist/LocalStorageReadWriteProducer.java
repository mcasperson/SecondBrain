package secondbrain.domain.persist;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import secondbrain.domain.config.MockConfig;
import secondbrain.domain.injection.Preferred;

/**
 * Produces a LocalStorageReadWrite instance based on the configuration.
 * Returns MockLocalStorageReadWrite when mock mode is enabled, otherwise FileLocalStorageReadWrite.
 */
@ApplicationScoped
public class LocalStorageReadWriteProducer {

    @Inject
    private MockConfig mockConfig;

    @Produces
    @Preferred
    @ApplicationScoped
    public LocalStorageReadWrite produceLocalStorageReadWrite(
            final FileLocalStorageReadWrite fileLocalStorageReadWrite,
            final MockLocalStorageReadWrite mockLocalStorageReadWrite) {
        if (mockConfig.isMock()) {
            return mockLocalStorageReadWrite;
        }

        return fileLocalStorageReadWrite;
    }
}

