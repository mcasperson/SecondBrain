package secondbrain.infrastructure.salesforce.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SalesforceOauthToken(@JsonProperty("client_id") String clientId,
                                   @JsonProperty("client_secret") String clientSecret,
                                   @JsonProperty("grant_type") String grantType) {
}
