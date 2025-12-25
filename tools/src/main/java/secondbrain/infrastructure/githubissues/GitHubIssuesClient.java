package secondbrain.infrastructure.githubissues;

import org.jspecify.annotations.Nullable;
import secondbrain.infrastructure.githubissues.api.GitHubIssue;

import java.util.List;

public interface GitHubIssuesClient {
    List<GitHubIssue> getIssues(
            final String token,
            final String organisation,
            final String repo,
            @Nullable final String since,
            @Nullable final String to,
            final List<String> labels,
            final String state);
}
