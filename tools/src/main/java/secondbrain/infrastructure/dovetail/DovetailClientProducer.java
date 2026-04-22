package secondbrain.infrastructure.dovetail;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import secondbrain.domain.config.MockConfig;
import secondbrain.domain.injection.Preferred;

/**
 * Produces a DovetailClient instance based on the configuration.
 */
@ApplicationScoped
public class DovetailClientProducer {

    @Inject
    private MockConfig mockConfig;

    @Produces
    @Preferred
    @ApplicationScoped
    public DovetailClient produceDovetailClient(
            final DovetailClientLive dovetailClientLive,
            final DovetailClientMock dovetailClientMock) {
        if (mockConfig.isMock()) {
            return dovetailClientMock;
        }
        return dovetailClientLive;
    }
}

