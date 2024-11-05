package secondbrain.infrastructure.oauth.linkedin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LinkedInOauthTokenResponse(String access_token) {
}
