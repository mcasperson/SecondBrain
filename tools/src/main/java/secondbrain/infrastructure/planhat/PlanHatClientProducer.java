package secondbrain.infrastructure.planhat;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import secondbrain.domain.config.MockConfig;
import secondbrain.domain.injection.Preferred;

public class PlanHatClientProducer {
    @Inject
    private MockConfig mockConfig;

    @Produces
    @Preferred
    @ApplicationScoped
    public PlanHatClient produceSlackClient(final PlanHatClientLive clientLive,
                                            final PlanHatClientMock clientMock) {
        if (mockConfig.isMock()) {
            return clientMock;
        }

        return clientLive;
    }
}
