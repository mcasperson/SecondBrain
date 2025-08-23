package secondbrain.infrastructure.githubissues.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubIssueLabels(Integer id, String name, String description) {
}
