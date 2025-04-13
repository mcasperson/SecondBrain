package secondbrain.infrastructure.github;

import jakarta.ws.rs.client.Client;
import org.eclipse.microprofile.faulttolerance.Retry;
import secondbrain.infrastructure.github.api.GitHubCommitAndDiff;
import secondbrain.infrastructure.github.api.GitHubCommitResponse;

import java.util.List;

public interface GitHubClient {
    @Retry
    List<GitHubCommitResponse> getCommitsInRange(Client client, String owner, String repo, String sha, String until, String since, String authorization);

    @Retry
    GitHubCommitResponse getCommit(Client client, String owner, String repo, String sha, String authorization);

    @Retry
    List<GitHubCommitAndDiff> getCommits(Client client, String owner, String repo, List<String> sha, String authorization);

    @Retry
    String getDiff(Client client, String owner, String repo, String sha, String authorization);
}
