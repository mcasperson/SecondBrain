package secondbrain.infrastructure.zendesk;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import secondbrain.domain.config.MockConfig;
import secondbrain.domain.injection.Preferred;

public class ZenDeskClientProducer {
    @Inject
    private MockConfig mockConfig;

    @Produces
    @Preferred
    @ApplicationScoped
    public ZenDeskClient produceSlackClient(final ZenDeskClientLive clientLive,
                                            final ZenDeskClientMock clientMock) {
        if (mockConfig.isMock()) {
            return clientMock;
        }

        return clientLive;
    }
}
