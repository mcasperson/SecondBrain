package secondbrain.infrastructure.oauth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OauthTokenResponse(String access_token) {
}
