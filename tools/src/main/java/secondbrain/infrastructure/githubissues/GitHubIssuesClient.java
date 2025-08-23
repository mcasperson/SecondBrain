package secondbrain.infrastructure.githubissues;

import secondbrain.infrastructure.githubissues.api.GitHubIssue;

import java.util.List;

public interface GitHubIssuesClient {
    List<GitHubIssue> getIssues(final String token, final String organisation, final String repo, final String since, final List<String> labels, final String state);
}
