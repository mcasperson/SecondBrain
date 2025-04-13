package secondbrain.infrastructure.github;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.faulttolerance.Retry;
import secondbrain.domain.concurrency.SemaphoreLender;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.response.ResponseValidation;
import secondbrain.infrastructure.github.api.GitHubCommitAndDiff;
import secondbrain.infrastructure.github.api.GitHubCommitResponse;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@ApplicationScoped
public class GitHubClientLive implements GitHubClient {
    private static final SemaphoreLender SEMAPHORE_LENDER = new SemaphoreLender(Constants.DEFAULT_SEMAPHORE_COUNT);

    @Inject
    private ResponseValidation responseValidation;

    @Retry
    @Override
    public List<GitHubCommitResponse> getCommitsInRange(final Client client, final String owner, final String repo, final String sha, final String until, final String since, final String authorization) {
        final String target = "https://api.github.com/repos/"
                + URLEncoder.encode(owner, StandardCharsets.UTF_8) + "/"
                + URLEncoder.encode(repo, StandardCharsets.UTF_8) + "/commits";

        return Try.withResources(() -> SEMAPHORE_LENDER.lend(client.target(target)
                        .queryParam("sha", sha)
                        .queryParam("until", until)
                        .queryParam("since", since)
                        .request()
                        .header("Authorization", authorization)
                        .header("Accept", MediaType.APPLICATION_JSON)
                        .get()))
                .of(response -> Try.of(() -> responseValidation.validate(response.getWrapped(), target))
                        .map(r -> List.of(r.readEntity(GitHubCommitResponse[].class)))
                        .get())
                .get();
    }

    @Retry
    @Override
    public GitHubCommitResponse getCommit(final Client client, final String owner, final String repo, final String sha, final String authorization) {
        final String target = "https://api.github.com/repos/"
                + URLEncoder.encode(owner, StandardCharsets.UTF_8) + "/"
                + URLEncoder.encode(repo, StandardCharsets.UTF_8) + "/commits/"
                + URLEncoder.encode(sha, StandardCharsets.UTF_8);

        return Try.withResources(() -> SEMAPHORE_LENDER.lend(client.target(target)
                        .request()
                        .header("Authorization", authorization)
                        .header("Accept", MediaType.APPLICATION_JSON)
                        .get()))
                .of(response -> Try.of(() -> responseValidation.validate(response.getWrapped(), target))
                        .map(r -> r.readEntity(GitHubCommitResponse.class))
                        .get())
                .get();
    }

    @Retry
    @Override
    public List<GitHubCommitAndDiff> getCommits(final Client client, final String owner, final String repo, final List<String> sha, final String authorization) {
        return sha.stream()
                .map(s -> new GitHubCommitAndDiff(
                        getCommit(client, owner, repo, s, authorization),
                        getDiff(client, owner, repo, s, authorization)))
                .toList();
    }

    @Retry
    @Override
    public String getDiff(final Client client, final String owner, final String repo, final String sha, final String authorization) {
        final String target = "https://api.github.com/repos/"
                + URLEncoder.encode(owner, StandardCharsets.UTF_8) + "/"
                + URLEncoder.encode(repo, StandardCharsets.UTF_8)
                + "/commits/" + URLEncoder.encode(sha, StandardCharsets.UTF_8);

        return Try.withResources(() -> SEMAPHORE_LENDER.lend(client.target(target)
                        .request()
                        .header("Authorization", authorization)
                        .header("Accept", "application/vnd.github.v3.diff")
                        .get()))
                .of(response -> Try.of(() -> responseValidation.validate(response.getWrapped(), target))
                        .map(r -> r.readEntity(String.class))
                        .get())
                .get();
    }
}
