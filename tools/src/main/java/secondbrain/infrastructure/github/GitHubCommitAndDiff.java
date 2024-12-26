package secondbrain.infrastructure.github;

public record GitHubCommitAndDiff(GitHubCommitResponse commit, String diff) {
}
