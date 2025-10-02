package secondbrain.infrastructure.salesforce.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SalesforceOauthTokenResponse(@JsonProperty("access_token") String accessToken,
                                           @JsonProperty("instance_url") String instanceUrl, String id,
                                           @JsonProperty("token_type") String tokenType,
                                           @JsonProperty("issued_at") String issuedAt, String signature) {
}
