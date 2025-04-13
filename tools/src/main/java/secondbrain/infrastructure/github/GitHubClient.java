package secondbrain.infrastructure.github;

import jakarta.ws.rs.client.Client;
import secondbrain.infrastructure.github.api.GitHubCommitAndDiff;
import secondbrain.infrastructure.github.api.GitHubCommitResponse;

import java.util.List;

public interface GitHubClient {
    List<GitHubCommitResponse> getCommitsInRange(Client client, String owner, String repo, String sha, String until, String since, String authorization);

    GitHubCommitResponse getCommit(Client client, String owner, String repo, String sha, String authorization);

    List<GitHubCommitAndDiff> getCommits(Client client, String owner, String repo, List<String> sha, String authorization);

    String getDiff(Client client, String owner, String repo, String sha, String authorization);
}
