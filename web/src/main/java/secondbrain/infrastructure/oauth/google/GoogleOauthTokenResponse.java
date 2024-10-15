package secondbrain.infrastructure.oauth.google;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GoogleOauthTokenResponse(String access_token) {
}
