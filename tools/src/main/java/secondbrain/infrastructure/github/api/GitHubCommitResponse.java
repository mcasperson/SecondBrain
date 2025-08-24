package secondbrain.infrastructure.github.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubCommitResponse(String sha, String html_url, GitHubCommitResponseCommit commit) {
    public String getDiffUrl() {
        return html_url + ".diff";
    }
}
