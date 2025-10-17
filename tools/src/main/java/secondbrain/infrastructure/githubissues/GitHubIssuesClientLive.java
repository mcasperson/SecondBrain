package secondbrain.infrastructure.githubissues;

import com.google.common.util.concurrent.RateLimiter;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.ArrayUtils;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.exceptions.Timeout;
import secondbrain.domain.httpclient.TimeoutHttpClientCaller;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.response.ResponseValidation;
import secondbrain.infrastructure.githubissues.api.GitHubIssue;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

@ApplicationScoped
public class GitHubIssuesClientLive implements GitHubIssuesClient {
    private static final int TTL_SECONDS = 60 * 60 * 24 * 90;
    private static final RateLimiter RATE_LIMITER = RateLimiter.create(Constants.DEFAULT_RATE_LIMIT_PER_SECOND);
    private static final long API_CONNECTION_TIMEOUT_SECONDS_DEFAULT = 10;
    private static final long API_CALL_TIMEOUT_SECONDS_DEFAULT = 60 * 2; // 2 minutes
    private static final long API_CALL_DELAY_SECONDS_DEFAULT = 30;
    private static final long CLIENT_TIMEOUT_BUFFER_SECONDS = 5;
    private static final int API_RETRIES = 3;
    private static final String API_CALL_TIMEOUT_MESSAGE = "Call timed out after " + API_CALL_TIMEOUT_SECONDS_DEFAULT + " seconds";

    @Inject
    private ResponseValidation responseValidation;

    @Inject
    @Preferred
    private LocalStorage localStorage;

    @Inject
    private Logger logger;

    @Inject
    private TimeoutHttpClientCaller httpClientCaller;

    private Client getClient() {
        final ClientBuilder clientBuilder = ClientBuilder.newBuilder();
        clientBuilder.connectTimeout(API_CONNECTION_TIMEOUT_SECONDS_DEFAULT, TimeUnit.SECONDS);
        // We want to use the timeoutService to handle timeouts, so we set the client timeout slightly longer.
        clientBuilder.readTimeout(API_CALL_TIMEOUT_SECONDS_DEFAULT + CLIENT_TIMEOUT_BUFFER_SECONDS, TimeUnit.SECONDS);
        return clientBuilder.build();
    }


    @Override
    public List<GitHubIssue> getIssues(final String token, final String organisation, final String repo, final String since, final String to, final List<String> labels, final String state) {
        return Arrays.stream(localStorage.getOrPutObject(
                                GitHubIssuesClientLive.class.getSimpleName(),
                                "GitHubIssuesV2",
                                DigestUtils.sha256Hex(organisation + repo + since + to + labels + state),
                                TTL_SECONDS,
                                GitHubIssue[].class,
                                () -> getIssuesApi(token, organisation, repo, since, to, labels, state, 1))
                        .result())
                .toList();
    }

    private GitHubIssue[] getIssuesApi(final String token, final String organisation, final String repo, final String since, final String to, final List<String> labels, final String state, final int page) {
        RATE_LIMITER.acquire();

        final String target = "https://api.github.com/repos/"
                + URLEncoder.encode(organisation, StandardCharsets.UTF_8) + "/"
                + URLEncoder.encode(repo, StandardCharsets.UTF_8) + "/issues" +
                "?page=" + page +
                "&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8) +
                "&since=" + URLEncoder.encode(since, StandardCharsets.UTF_8) +
                "&labels=" + URLEncoder.encode(String.join(",", labels), StandardCharsets.UTF_8) +
                "&per_page=100";

        logger.info("Fetching GitHub issues from: " + target);

        return httpClientCaller.call(
                this::getClient,
                client -> client.target(target)
                        .request("application/vnd.github+json")
                        .header("Authorization", "Bearer " + token)
                        .get(),
                response -> Try.of(() -> responseValidation.validate(response, target))
                        .map(r -> r.readEntity(GitHubIssue[].class))
                        // Recurse if there is a next page, and we have not gone too far
                        .map(r -> ArrayUtils.addAll(
                                r,
                                r.length == 100
                                        ? getIssuesApi(token, organisation, repo, since, to, labels, state, page + 1)
                                        : new GitHubIssue[]{}))
                        .get(),
                e -> new RuntimeException("Failed to get issues from GitHub API", e),
                () -> {
                    throw new Timeout(API_CALL_TIMEOUT_MESSAGE);
                },
                API_CALL_TIMEOUT_SECONDS_DEFAULT,
                API_CALL_DELAY_SECONDS_DEFAULT,
                API_RETRIES);
    }
}
