package secondbrain.infrastructure.githubissues.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubIssue(Long id, String title, String body, String description, String state,
                          List<GitHubIssueLabels> labels, @JsonProperty("html_url") String htmlUrl) {
}
