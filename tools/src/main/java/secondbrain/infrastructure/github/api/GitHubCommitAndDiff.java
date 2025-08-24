package secondbrain.infrastructure.github.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubCommitAndDiff(GitHubCommitResponse commit, String diff) {
    public String getMessageAndDiff() {
        if (commit != null && commit.commit() != null && commit.commit().message() != null) {
            return "Commit message:\n" + commit.commit().message() + "\nDiff:\n" + getDiff();
        }

        return getDiff();
    }

    public String getDiff() {
        return Objects.requireNonNullElse(diff, "");
    }
}
