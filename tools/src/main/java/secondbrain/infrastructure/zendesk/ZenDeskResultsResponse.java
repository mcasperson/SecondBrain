package secondbrain.infrastructure.zendesk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ZenDeskResultsResponse(String id) {
}
