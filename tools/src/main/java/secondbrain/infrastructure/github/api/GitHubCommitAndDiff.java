package secondbrain.infrastructure.github.api;

public record GitHubCommitAndDiff(GitHubCommitResponse commit, String diff) {
}
