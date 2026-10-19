package secondbrain.infrastructure.githubissues;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.apache.commons.codec.digest.DigestUtils;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import secondbrain.domain.exceptions.InvalidResponse;
import secondbrain.domain.httpclient.TimeoutHttpClientCaller;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.response.OkResponseValidation;
import secondbrain.domain.test.*;
import secondbrain.domain.web.ClientConstructor;
import secondbrain.infrastructure.githubissues.api.GitHubIssue;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The client shares a static rate limiter of one request per second, so each test makes as few HTTP requests as
 * possible.
 */
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(GitHubIssuesClientLive.class)
@AddBeanClasses(OkResponseValidation.class)
@AddBeanClasses(Loggers.class)
// Issues are built with null fields to match real responses
@SuppressWarnings("NullAway")
class GitHubIssuesClientLiveTest {
    private static final String TOKEN = "gh-token";
    private static final String TOOL = "GitHubIssuesClientLive";
    private static final String SOURCE = "GitHubIssuesV2";
    private static final long TTL = 60L * 60 * 24 * 90;
    private static final String SINCE = "2024-01-01T00:00:00Z";
    private static final String TO = "2024-02-01T00:00:00Z";

    private final RecordingLocalStorage localStorage = new RecordingLocalStorage();
    private final FakeJaxRs http = new FakeJaxRs();
    private final DirectHttpClientCaller httpClientCaller = new DirectHttpClientCaller();

    @Inject
    @Preferred
    private GitHubIssuesClientLive client;

    @Produces
    @Preferred
    LocalStorage produceLocalStorage() {
        return localStorage;
    }

    @Produces
    TimeoutHttpClientCaller produceHttpClientCaller() {
        return httpClientCaller;
    }

    @Produces
    ClientConstructor produceClientConstructor() {
        return new FixedClientConstructor(http.client);
    }

    @BeforeEach
    void registerConfig() {
        TestConfigUtil.registerConfig(Map.of());
    }

    private static GitHubIssue issue(final long number) {
        return new GitHubIssue(number, 1000 + number, "Issue " + number, "Body " + number, null, "open", List.of(),
                "https://github.com/acme/api/issues/" + number);
    }

    private static GitHubIssue[] issues(final int from, final int count) {
        return IntStream.range(from, from + count).mapToObj(GitHubIssuesClientLiveTest::issue).toArray(GitHubIssue[]::new);
    }

    @Test
    void getIssuesQueriesTheEncodedRepositoryAndCachesTheResult() {
        http.enqueue(FakeJaxRs.response(200, new GitHubIssue[]{issue(1), issue(2)}));

        final List<GitHubIssue> result = client.getIssues(TOKEN, "acme corp", "api/v2", SINCE, TO, List.of("bug", "help wanted"), "open");

        assertEquals(List.of(issue(1), issue(2)), result);
        // The "to" date is only part of the cache key, it is not sent to the API
        assertEquals(List.of("https://api.github.com/repos/acme+corp/api%2Fv2/issues?page=1&state=open&since=2024-01-01T00%3A00%3A00Z"
                + "&labels=bug%2Chelp+wanted&per_page=100"), http.targets());
        assertEquals(List.of("Bearer " + TOKEN), http.headers("Authorization"));
        assertEquals(List.of(new RecordingLocalStorage.Call(TOOL, SOURCE,
                DigestUtils.sha256Hex("acme corp" + "api/v2" + SINCE + TO + "[bug, help wanted]" + "open"), TTL)), localStorage.calls);
        // Each page is retried 3 times with a 30 second delay
        assertEquals(List.of(3), httpClientCaller.maxRetries());
        assertEquals(List.of(30L), httpClientCaller.retryDelays());
    }

    @Test
    void getIssuesSendsAnEmptySinceAndNoLabelsWhenTheyAreMissing() {
        http.enqueue(FakeJaxRs.response(200, new GitHubIssue[]{}));

        assertEquals(List.of(), client.getIssues(TOKEN, "acme", "api", null, null, List.of(), "closed"));
        assertEquals(List.of("https://api.github.com/repos/acme/api/issues?page=1&state=closed&since=&labels=&per_page=100"), http.targets());
        // Missing dates appear in the cache key as "null"
        assertEquals(DigestUtils.sha256Hex("acmeapinullnull[]closed"), localStorage.calls.getFirst().promptHash());
    }

    @Test
    void getIssuesRequestsTheNextPageUntilAPageIsNotFull() {
        http.enqueue(FakeJaxRs.response(200, issues(0, 100)))
                .enqueue(FakeJaxRs.response(200, issues(100, 1)));

        final List<GitHubIssue> result = client.getIssues(TOKEN, "acme", "api", SINCE, TO, List.of("bug"), "all");

        assertEquals(101, result.size());
        assertEquals(issue(0), result.getFirst());
        assertEquals(issue(100), result.getLast());
        assertEquals(List.of(
                "https://api.github.com/repos/acme/api/issues?page=1&state=all&since=2024-01-01T00%3A00%3A00Z&labels=bug&per_page=100",
                "https://api.github.com/repos/acme/api/issues?page=2&state=all&since=2024-01-01T00%3A00%3A00Z&labels=bug&per_page=100"), http.targets());
        // Both pages are cached as a single entry
        assertEquals(1, localStorage.calls.size());
    }

    @Test
    void getIssuesUsesTheCache() {
        localStorage.prime(SOURCE, new GitHubIssue[]{issue(7)});

        assertEquals(List.of(issue(7)), client.getIssues(TOKEN, "acme", "api", SINCE, TO, List.of(), "open"));
        assertEquals(List.of(), http.targets());
        assertEquals(0, httpClientCaller.calls());
    }

    @Test
    void getIssuesThrowsForANullCacheEntry() {
        localStorage.primeNull(SOURCE);

        assertThrows(NoSuchElementException.class, () -> client.getIssues(TOKEN, "acme", "api", SINCE, TO, List.of(), "open"));
        assertEquals(List.of(), http.targets());
    }

    @Test
    void getIssuesWrapsApiFailures() {
        http.enqueue(FakeJaxRs.response(500, "boom"));

        final RuntimeException ex = assertThrows(RuntimeException.class, () -> client.getIssues(TOKEN, "acme", "api", SINCE, TO, List.of(), "open"));

        assertEquals("Failed to get issues from GitHub API", ex.getMessage());
        assertInstanceOf(InvalidResponse.class, ex.getCause());
    }
}
