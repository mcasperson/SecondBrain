package secondbrain.infrastructure.zendesk.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ZenDeskOrganizationResponse(ZenDeskOrganizationItemResponse organization) {
}
