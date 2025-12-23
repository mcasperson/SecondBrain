package secondbrain.infrastructure.githubissues;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import secondbrain.domain.config.MockConfig;
import secondbrain.domain.injection.Preferred;

@ApplicationScoped
public class GitHubIssuesClientProducer {
    @Inject
    private MockConfig mockConfig;

    @SuppressWarnings("NullAway")
    @Produces
    @Preferred
    @ApplicationScoped
    public GitHubIssuesClient produceSlackClient(final GitHubIssuesClientLive liveClient) {
        if (mockConfig.isMock()) {
            // Todo: implement and return GitHubIssuesClientMock when needed
            return null;
        }

        return liveClient;
    }
}
