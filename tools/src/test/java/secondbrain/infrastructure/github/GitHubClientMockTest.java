package secondbrain.infrastructure.github;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.test.RecordingLlmClient;
import secondbrain.infrastructure.github.api.GitHubCommitAndDiff;
import secondbrain.infrastructure.github.api.GitHubCommitResponse;
import secondbrain.infrastructure.llm.LlmClient;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(GitHubClientMock.class)
class GitHubClientMockTest {
    private static final String OWNER = "octo";
    private static final String REPO = "widgets";
    private static final String AUTH = "token";

    private final Client httpClient = mock(Client.class);
    private final AtomicInteger llmCalls = new AtomicInteger();
    private final RecordingLlmClient llmClient = new RecordingLlmClient()
            .respondWith(document -> "diff-" + llmCalls.incrementAndGet());

    @Inject
    private GitHubClientMock client;

    @Produces
    @Preferred
    LlmClient produceLlmClient() {
        return llmClient;
    }

    private static void assertCommit(final String sha, final GitHubCommitResponse commit) {
        assertEquals(sha, commit.sha());
        assertEquals("https://github.com/octo/widgets/commit/" + sha, commit.html_url());
        assertNull(commit.commit());
    }

    @Test
    void getCommitBuildsCommitUrlFromOwnerRepoAndSha() {
        final GitHubCommitResponse commit = client.getCommit(httpClient, OWNER, REPO, "abc123", AUTH);

        assertCommit("abc123", commit);
        assertTrue(llmClient.requests.isEmpty());
    }

    @Test
    void getDiffReturnsLlmDiffForTheRepository() {
        final String diff = client.getDiff(httpClient, OWNER, REPO, "abc123", AUTH);

        assertEquals("diff-1", diff);
        final String prompt = llmClient.requests.get(0).document().getPrompts().get(0);
        assertTrue(prompt.contains("git diff"), prompt);
        assertTrue(prompt.contains("the widgets repository"), prompt);
    }

    @Test
    void getCommitsPairsEachShaWithItsCommitAndDiffInOrder() {
        final List<GitHubCommitAndDiff> commits = client.getCommits(httpClient, OWNER, REPO, List.of("sha1", "sha2"), AUTH);

        assertEquals(2, commits.size());
        assertCommit("sha1", commits.get(0).commit());
        assertEquals("diff-1", commits.get(0).diff());
        assertCommit("sha2", commits.get(1).commit());
        assertEquals("diff-2", commits.get(1).diff());
        assertEquals(2, llmClient.requests.size());
    }

    @Test
    void getCommitsReturnsEmptyListForNoShas() {
        assertEquals(List.of(), client.getCommits(httpClient, OWNER, REPO, List.of(), AUTH));
        assertTrue(llmClient.requests.isEmpty());
    }

    @Test
    void getCommitsInRangeGeneratesBetweenThreeAndSevenUniqueCommitsWithFortyCharacterShas() {
        final List<GitHubCommitResponse> commits = client.getCommitsInRange(httpClient, OWNER, REPO, "main", "2024-02-01", "2024-01-01", AUTH);

        assertTrue(commits.size() >= 3 && commits.size() <= 7, "Unexpected commit count " + commits.size());
        for (final GitHubCommitResponse commit : commits) {
            assertTrue(commit.sha().matches("[0-9a-f]{40}"), commit.sha());
            assertEquals("https://github.com/" + OWNER + "/" + REPO + "/commit/" + commit.sha(), commit.html_url());
        }
        assertEquals(commits.size(), commits.stream().map(GitHubCommitResponse::sha).distinct().count());
        assertTrue(llmClient.requests.isEmpty());
    }
}
