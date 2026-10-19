package secondbrain.infrastructure.github;

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
import secondbrain.domain.exceptions.MissingResponse;
import secondbrain.domain.exceptions.UnauthorizedResponse;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.response.OkResponseValidation;
import secondbrain.domain.test.FakeJaxRs;
import secondbrain.domain.test.RecordingLocalStorage;
import secondbrain.domain.test.TestConfigUtil;
import secondbrain.infrastructure.github.api.GitHubCommitAndDiff;
import secondbrain.infrastructure.github.api.GitHubCommitResponse;
import secondbrain.infrastructure.github.api.GitHubCommitResponseCommit;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(GitHubClientLive.class)
@AddBeanClasses(OkResponseValidation.class)
@AddBeanClasses(Loggers.class)
class GitHubClientLiveTest {
    private static final String AUTH = "Bearer gh-token";
    private static final String TOOL = "GitHubIssuesClientLive";
    private static final String SOURCE = "GitHubIssuesV2";
    private static final String COMMIT_SOURCE = "GitHubCommitV3";
    private static final String DIFF_SOURCE = "GitHubDiffV3";
    private static final long TTL = 60L * 60 * 24 * 90;
    private static final String SINCE = "2024-01-01T00:00:00";
    private static final String UNTIL = "2024-02-01T00:00:00";

    private final RecordingLocalStorage localStorage = new RecordingLocalStorage();
    private final FakeJaxRs http = new FakeJaxRs();

    @Inject
    @Preferred
    private GitHubClientLive client;

    @Produces
    @Preferred
    LocalStorage produceLocalStorage() {
        return localStorage;
    }

    @BeforeEach
    void registerConfig() {
        TestConfigUtil.registerConfig(Map.of());
    }

    private static GitHubCommitResponse commit(final String sha) {
        return new GitHubCommitResponse(sha, "https://github.com/acme/api/commit/" + sha, new GitHubCommitResponseCommit("Message " + sha));
    }

    @Test
    void getCommitsInRangeQueriesTheEncodedRepositoryWithTheRange() {
        http.enqueue(FakeJaxRs.response(200, new GitHubCommitResponse[]{commit("aaa"), commit("bbb")}));

        final List<GitHubCommitResponse> result = client.getCommitsInRange(http.client, "acme corp", "api/v2", "main", UNTIL, SINCE, AUTH);

        assertEquals(List.of(commit("aaa"), commit("bbb")), result);
        assertEquals(List.of("https://api.github.com/repos/acme+corp/api%2Fv2/commits"), http.targets());
        assertEquals(List.of("main"), http.queryParams("sha"));
        assertEquals(List.of(UNTIL), http.queryParams("until"));
        assertEquals(List.of(SINCE), http.queryParams("since"));
        assertEquals(List.of(AUTH), http.headers("Authorization"));
        assertEquals(List.of("application/json"), http.headers("Accept"));
        assertEquals(List.of(new RecordingLocalStorage.Call(TOOL, SOURCE, DigestUtils.sha256Hex("acme corp" + "api/v2" + SINCE + UNTIL + "main"), TTL)),
                localStorage.calls);
    }

    @Test
    void getCommitsInRangeReturnsAnEmptyListForAnEmptyResponse() {
        http.enqueue(FakeJaxRs.response(200, new GitHubCommitResponse[]{}));

        assertEquals(List.of(), client.getCommitsInRange(http.client, "acme", "api", "main", UNTIL, SINCE, AUTH));
    }

    @Test
    void getCommitsInRangeUsesTheCache() {
        localStorage.prime(SOURCE, new GitHubCommitResponse[]{commit("cached")});

        assertEquals(List.of(commit("cached")), client.getCommitsInRange(http.client, "acme", "api", "main", UNTIL, SINCE, AUTH));
        assertEquals(List.of(), http.targets());
    }

    @Test
    void getCommitsInRangePropagatesApiFailures() {
        http.enqueue(FakeJaxRs.response(500, "boom"));

        assertThrows(InvalidResponse.class, () -> client.getCommitsInRange(http.client, "acme", "api", "main", UNTIL, SINCE, AUTH));
    }

    @Test
    void getCommitRequestsASingleEncodedCommit() {
        http.enqueue(FakeJaxRs.response(200, commit("a/b")));

        assertEquals(commit("a/b"), client.getCommit(http.client, "acme", "api", "a/b", AUTH));
        assertEquals(List.of("https://api.github.com/repos/acme/api/commits/a%2Fb"), http.targets());
        assertEquals(List.of("application/json"), http.headers("Accept"));
        assertEquals(List.of(new RecordingLocalStorage.Call(TOOL, COMMIT_SOURCE, DigestUtils.sha256Hex("acme" + "api" + "a/b"), TTL)), localStorage.calls);
    }

    @Test
    void getCommitPropagatesMissingCommits() {
        http.enqueue(FakeJaxRs.response(404, ""));

        assertThrows(MissingResponse.class, () -> client.getCommit(http.client, "acme", "api", "nope", AUTH));
    }

    @Test
    void getCommitThrowsForANullCacheEntry() {
        localStorage.primeNull(COMMIT_SOURCE);

        assertThrows(NoSuchElementException.class, () -> client.getCommit(http.client, "acme", "api", "aaa", AUTH));
        assertEquals(List.of(), http.targets());
    }

    @Test
    void getDiffRequestsTheDiffMediaType() {
        http.enqueue(FakeJaxRs.response(200, "diff --git a/x b/x"));

        assertEquals("diff --git a/x b/x", client.getDiff(http.client, "acme", "api", "aaa", AUTH));
        assertEquals(List.of("https://api.github.com/repos/acme/api/commits/aaa"), http.targets());
        assertEquals(List.of("application/vnd.github.v3.diff"), http.headers("Accept"));
        assertEquals(List.of(AUTH), http.headers("Authorization"));
    }

    @Test
    void getDiffThrowsForANullCacheEntry() {
        localStorage.primeNull(DIFF_SOURCE);
        assertThrows(NoSuchElementException.class, () -> client.getDiff(http.client, "acme", "api", "aaa", AUTH));
    }

    @Test
    void getDiffPropagatesApiFailures() {
        http.enqueue(FakeJaxRs.response(401, ""));

        assertThrows(UnauthorizedResponse.class, () -> client.getDiff(http.client, "acme", "api", "aaa", AUTH));
    }

    @Test
    void getCommitsLoadsTheCommitAndDiffForEachShaInOrder() {
        http.enqueue(FakeJaxRs.response(200, commit("aaa")))
                .enqueue(FakeJaxRs.response(200, "diff of aaa"))
                .enqueue(FakeJaxRs.response(200, commit("bbb")))
                .enqueue(FakeJaxRs.response(200, "diff of bbb"));

        final List<GitHubCommitAndDiff> result = client.getCommits(http.client, "acme", "api", List.of("aaa", "bbb"), AUTH);

        assertEquals(List.of(new GitHubCommitAndDiff(commit("aaa"), "diff of aaa"), new GitHubCommitAndDiff(commit("bbb"), "diff of bbb")), result);
        assertEquals(List.of("application/json", "application/vnd.github.v3.diff", "application/json", "application/vnd.github.v3.diff"),
                http.headers("Accept"));
    }

    @Test
    void getCommitsReturnsNothingForNoShas() {
        assertEquals(List.of(), client.getCommits(http.client, "acme", "api", List.of(), AUTH));
        assertEquals(List.of(), http.targets());
    }

    @Test
    void commitAndDiffAreCachedSeparately() {
        http.enqueue(FakeJaxRs.response(200, commit("aaa")))
                .enqueue(FakeJaxRs.response(200, "diff of aaa"));

        client.getCommit(http.client, "acme", "api", "aaa", AUTH);
        client.getDiff(http.client, "acme", "api", "aaa", AUTH);

        final String hash = DigestUtils.sha256Hex("acmeapiaaa");
        assertEquals(List.of(
                new RecordingLocalStorage.Call(TOOL, COMMIT_SOURCE, hash, TTL),
                new RecordingLocalStorage.Call(TOOL, DIFF_SOURCE, hash, TTL)), localStorage.calls);
    }

    @Test
    void getDiffDoesNotReturnACachedCommit() {
        localStorage.prime(COMMIT_SOURCE, commit("aaa"));
        http.enqueue(FakeJaxRs.response(200, "diff of aaa"));

        assertEquals(commit("aaa"), client.getCommit(http.client, "acme", "api", "aaa", AUTH));
        assertEquals("diff of aaa", client.getDiff(http.client, "acme", "api", "aaa", AUTH));
        assertEquals(List.of("application/vnd.github.v3.diff"), http.headers("Accept"));
    }

    @Test
    void getCommitDoesNotReturnACachedDiff() {
        localStorage.prime(DIFF_SOURCE, "diff of aaa");
        http.enqueue(FakeJaxRs.response(200, commit("aaa")));

        assertEquals("diff of aaa", client.getDiff(http.client, "acme", "api", "aaa", AUTH));
        assertEquals(commit("aaa"), client.getCommit(http.client, "acme", "api", "aaa", AUTH));
        assertEquals(List.of("application/json"), http.headers("Accept"));
    }
}
