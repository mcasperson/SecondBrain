package secondbrain.infrastructure.github.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubCommitAndDiff(GitHubCommitResponse commit, String diff) {
    public String getMessageAndDiff() {
        return "Commit message:\n" + commit.commit().message() + "\nDiff:\n" + diff;
    }
}
