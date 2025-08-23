package secondbrain.infrastructure.githubissues.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubIssue(Long number, Long id, String title, String body, String description, String state,
                          List<GitHubIssueLabels> labels, @JsonProperty("html_url") String htmlUrl) {

    public Long getNumber() {
        return Objects.requireNonNullElse(number(), 0L);
    }
}
