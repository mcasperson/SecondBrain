package secondbrain.infrastructure.github;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.faulttolerance.Retry;
import secondbrain.domain.response.ResponseValidation;

import java.util.List;

@ApplicationScoped
public class GitHubClient {
    @Inject
    private ResponseValidation responseValidation;

    @Retry
    public List<GitHubCommitResponse> getCommits(final Client client, final String owner, final String repo, final String sha, final String until, final String since, final String authorization) {
        return Try.withResources(() -> client.target("https://api.github.com/repos/" + owner + "/" + repo + "/commits")
                        .queryParam("sha", sha)
                        .queryParam("until", until)
                        .queryParam("since", since)
                        .request()
                        .header("Authorization", authorization)
                        .header("Accept", MediaType.APPLICATION_JSON)
                        .get())
                .of(response -> Try.of(() -> responseValidation.validate(response))
                        .map(r -> List.of(r.readEntity(GitHubCommitResponse[].class)))
                        .get())
                .get();
    }

    @Retry
    public GitHubCommitResponse getCommit(final Client client, final String owner, final String repo, final String sha, final String authorization) {
        return Try.withResources(() -> client.target("https://api.github.com/repos/" + owner + "/" + repo + "/commits/" + sha)
                        .request()
                        .header("Authorization", authorization)
                        .header("Accept", MediaType.APPLICATION_JSON)
                        .get())
                .of(response -> Try.of(() -> responseValidation.validate(response))
                        .map(r -> r.readEntity(GitHubCommitResponse.class))
                        .get())
                .get();
    }

    @Retry
    public String getDiff(final Client client, final String owner, final String repo, final String sha, final String authorization) {
        return Try.withResources(() -> client.target("https://api.github.com/repos/" + owner + "/" + repo + "/commits/" + sha)
                        .request()
                        .header("Authorization", authorization)
                        .header("Accept", "application/vnd.github.v3.diff")
                        .get())
                .of(response -> Try.of(() -> responseValidation.validate(response))
                        .map(r -> r.readEntity(String.class))
                        .get())
                .get();
    }
}
