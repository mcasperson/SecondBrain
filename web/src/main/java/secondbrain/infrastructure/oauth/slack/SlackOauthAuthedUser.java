package secondbrain.infrastructure.oauth.slack;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SlackOauthAuthedUser(String access_token) {
}
