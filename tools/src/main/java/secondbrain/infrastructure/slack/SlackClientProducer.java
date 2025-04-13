package secondbrain.infrastructure.slack;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import secondbrain.domain.config.MockConfig;
import secondbrain.domain.injection.Preferred;

@ApplicationScoped
public class SlackClientProducer {
    @Inject
    private MockConfig mockConfig;

    @Produces
    @Preferred
    @ApplicationScoped
    public SlackClient produceSlackClient(final SlackClientLive clientLive,
                                          final SlackClientMock clientMock) {
        if (mockConfig.isMock()) {
            return clientMock;
        }

        return clientLive;
    }
}
