package secondbrain.infrastructure.github;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.faulttolerance.Retry;
import secondbrain.domain.response.ResponseValidation;

import java.util.List;
import java.util.logging.Logger;

@ApplicationScoped
public class GitHubClient {
    @Inject
    private ResponseValidation responseValidation;

    @Retry
    public List<GitHubCommitResponse> getCommits(@NotNull final Client client, @NotNull final String owner, @NotNull final String repo, @NotNull final String sha, @NotNull final String until, @NotNull final String since, @NotNull final String authorization) {
        return Try.withResources(() -> client.target("https://api.github.com/repos/" + owner + "/" + repo + "/commits")
                .queryParam("sha", sha)
                .queryParam("until", until)
                .queryParam("since", since)
                .request()
                .header("Authorization", authorization)
                .header("Accept", MediaType.APPLICATION_JSON)
                .get())
                .of(responseValidation::validate)
                .map(response -> List.of(response.readEntity(GitHubCommitResponse[].class)))
                .get();
    }

    @Retry
    public String getDiff(@NotNull final Client client, @NotNull final String owner, @NotNull final String repo, @NotNull final String sha, @NotNull final String authorization) {
        return Try.withResources(() -> client.target("https://api.github.com/repos/" + owner + "/" + repo + "/commits/" + sha)
                .request()
                .header("Authorization", authorization)
                .header("Accept", "application/vnd.github.v3.diff")
                .get())
                .of(responseValidation::validate)
                .map(response -> response.readEntity(String.class))
                .get();
    }
}
