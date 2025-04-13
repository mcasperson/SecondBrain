package secondbrain.infrastructure.github;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import secondbrain.domain.config.MockConfig;
import secondbrain.domain.injection.Preferred;

@ApplicationScoped
public class GitHubClientProducer {
    @Inject
    private MockConfig mockConfig;

    @Produces
    @Preferred
    @ApplicationScoped
    public GitHubClient produceSlackClient(final GitHubClientLive clientLive,
                                           final GitHubClientMock clientMock) {
        if (mockConfig.isMock()) {
            return clientMock;
        }

        return clientLive;
    }
}
