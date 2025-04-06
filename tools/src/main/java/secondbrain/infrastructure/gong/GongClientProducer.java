package secondbrain.infrastructure.gong;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import secondbrain.domain.config.MockConfig;
import secondbrain.domain.injection.Preferred;

/**
 * Produces a GongClient instance based on the configuration.
 */
public class GongClientProducer {

    @Inject
    private MockConfig mockConfig;

    @Produces
    @Preferred
    @ApplicationScoped
    public GongClient produceGongClient(final GongClientLive gongClientLive,
                                        final GongClientMock gongClientMock) {
        if (mockConfig.isMock()) {
            return gongClientMock;
        }

        return gongClientLive;
    }
}
