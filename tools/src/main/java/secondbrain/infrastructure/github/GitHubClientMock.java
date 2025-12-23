package secondbrain.infrastructure.github;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import secondbrain.domain.injection.Preferred;
import secondbrain.infrastructure.github.api.GitHubCommitAndDiff;
import secondbrain.infrastructure.github.api.GitHubCommitResponse;
import secondbrain.infrastructure.llm.LlmClient;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@SuppressWarnings("NullAway")
@ApplicationScoped
public class GitHubClientMock implements GitHubClient {
    @Inject
    @Preferred
    private LlmClient llmClient;

    @Override
    public List<GitHubCommitResponse> getCommitsInRange(final Client client, final String owner, final String repo, final String sha, final String until, final String since, final String authorization) {
        int commitCount = 3 + (int) (Math.random() * 5); // Random number of commits between 3-7
        List<GitHubCommitResponse> commits = new ArrayList<>();

        for (int i = 0; i < commitCount; i++) {
            String mockSha = generateMockSha();
            String repoUrl = "https://github.com/" + owner + "/" + repo;
            String commitUrl = repoUrl + "/commit/" + mockSha;

            commits.add(new GitHubCommitResponse(mockSha, commitUrl, null));
        }

        return commits;
    }

    @Override
    public GitHubCommitResponse getCommit(Client client, String owner, String repo, String sha, String authorization) {
        String repoUrl = "https://github.com/" + owner + "/" + repo;
        String commitUrl = repoUrl + "/commit/" + sha;

        return new GitHubCommitResponse(sha, commitUrl, null);
    }

    @Override
    public List<GitHubCommitAndDiff> getCommits(Client client, String owner, String repo, List<String> shas, String authorization) {
        List<GitHubCommitAndDiff> commitAndDiffs = new ArrayList<>();

        for (String sha : shas) {
            GitHubCommitResponse commit = getCommit(client, owner, repo, sha, authorization);
            String diff = getDiff(client, owner, repo, sha, authorization);

            commitAndDiffs.add(new GitHubCommitAndDiff(commit, diff));
        }

        return commitAndDiffs;
    }

    @Override
    public String getDiff(Client client, String owner, String repo, String sha, String authorization) {
        return llmClient.call(
                "Generate a small but realistic git diff for a commit. Include file additions, modifications with context. " +
                        "Format like a real git diff with --- and +++ headers and @@ sections. " +
                        "It should be for a code file in the " + repo + " repository.");
    }

    private String generateMockSha() {
        // Generate a random SHA-like string
        return UUID.randomUUID().toString().replace("-", "").substring(0, 40);
    }
}
