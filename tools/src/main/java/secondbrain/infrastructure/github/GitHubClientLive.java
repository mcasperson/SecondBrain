package secondbrain.infrastructure.github;

import com.google.common.util.concurrent.RateLimiter;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.core.MediaType;
import org.apache.commons.codec.digest.DigestUtils;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.response.ResponseValidation;
import secondbrain.infrastructure.github.api.GitHubCommitAndDiff;
import secondbrain.infrastructure.github.api.GitHubCommitResponse;
import secondbrain.infrastructure.githubissues.GitHubIssuesClientLive;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

@ApplicationScoped
public class GitHubClientLive implements GitHubClient {
    private static final RateLimiter RATE_LIMITER = RateLimiter.create(Constants.DEFAULT_RATE_LIMIT_PER_SECOND);
    private static final int TTL_SECONDS = 60 * 60 * 24 * 90;

    @Inject
    private ResponseValidation responseValidation;

    @Inject
    private Logger logger;

    @Inject
    private LocalStorage localStorage;

    @Override
    public List<GitHubCommitResponse> getCommitsInRange(final Client client, final String owner, final String repo, final String sha, final String until, final String since, final String authorization) {
        return Arrays.stream(localStorage.getOrPutObject(
                        GitHubIssuesClientLive.class.getSimpleName(),
                        "GitHubIssuesV2",
                        DigestUtils.sha256Hex(owner + repo + since + until + sha),
                        TTL_SECONDS,
                        GitHubCommitResponse[].class,
                        () -> getCommitsInRangeApi(client, owner, repo, sha, until, since, authorization)))
                .toList();
    }

    private GitHubCommitResponse[] getCommitsInRangeApi(final Client client, final String owner, final String repo, final String sha, final String until, final String since, final String authorization) {
        RATE_LIMITER.acquire();

        final String target = "https://api.github.com/repos/"
                + URLEncoder.encode(owner, StandardCharsets.UTF_8) + "/"
                + URLEncoder.encode(repo, StandardCharsets.UTF_8) + "/commits";

        return Try.of(() -> client.target(target)
                        .queryParam("sha", sha)
                        .queryParam("until", until)
                        .queryParam("since", since)
                        .request()
                        .header("Authorization", authorization)
                        .header("Accept", MediaType.APPLICATION_JSON)
                        .get())
                .map(response -> Try.of(() -> responseValidation.validate(response, target))
                        .map(r -> r.readEntity(GitHubCommitResponse[].class))
                        .onFailure(e -> logger.warning("Failed to get commits in range from GitHub: " + e.getMessage()))
                        .get())
                .get();
    }

    @Override
    public GitHubCommitResponse getCommit(final Client client, final String owner, final String repo, final String sha, final String authorization) {
        return localStorage.getOrPutObject(
                GitHubIssuesClientLive.class.getSimpleName(),
                "GitHubIssuesV2",
                DigestUtils.sha256Hex(owner + repo + sha),
                TTL_SECONDS,
                GitHubCommitResponse.class,
                () -> getCommitApi(client, owner, repo, sha, authorization));
    }

    private GitHubCommitResponse getCommitApi(final Client client, final String owner, final String repo, final String sha, final String authorization) {
        RATE_LIMITER.acquire();

        final String target = "https://api.github.com/repos/"
                + URLEncoder.encode(owner, StandardCharsets.UTF_8) + "/"
                + URLEncoder.encode(repo, StandardCharsets.UTF_8) + "/commits/"
                + URLEncoder.encode(sha, StandardCharsets.UTF_8);

        return Try.of(() -> client.target(target)
                        .request()
                        .header("Authorization", authorization)
                        .header("Accept", MediaType.APPLICATION_JSON)
                        .get())
                .map(response -> Try.of(() -> responseValidation.validate(response, target))
                        .map(r -> r.readEntity(GitHubCommitResponse.class))
                        .onFailure(e -> logger.warning("Failed to get commit from GitHub: " + e.getMessage()))
                        .get())
                .get();
    }

    @Override
    public List<GitHubCommitAndDiff> getCommits(final Client client, final String owner, final String repo, final List<String> sha, final String authorization) {
        return sha.stream()
                .map(s -> new GitHubCommitAndDiff(
                        getCommit(client, owner, repo, s, authorization),
                        getDiff(client, owner, repo, s, authorization)))
                .toList();
    }

    @Override
    public String getDiff(final Client client, final String owner, final String repo, final String sha, final String authorization) {
        return localStorage.getOrPutObject(
                GitHubIssuesClientLive.class.getSimpleName(),
                "GitHubIssuesV2",
                DigestUtils.sha256Hex(owner + repo + sha),
                TTL_SECONDS,
                String.class,
                () -> getDiffApi(client, owner, repo, sha, authorization));
    }
    
    private String getDiffApi(final Client client, final String owner, final String repo, final String sha, final String authorization) {
        RATE_LIMITER.acquire();

        final String target = "https://api.github.com/repos/"
                + URLEncoder.encode(owner, StandardCharsets.UTF_8) + "/"
                + URLEncoder.encode(repo, StandardCharsets.UTF_8)
                + "/commits/" + URLEncoder.encode(sha, StandardCharsets.UTF_8);

        return Try.of(() -> client.target(target)
                        .request()
                        .header("Authorization", authorization)
                        .header("Accept", "application/vnd.github.v3.diff")
                        .get())
                .map(response -> Try.of(() -> responseValidation.validate(response, target))
                        .map(r -> r.readEntity(String.class))
                        .onFailure(e -> logger.warning("Failed to get diff from GitHub: " + e.getMessage()))
                        .get())
                .get();
    }
}
