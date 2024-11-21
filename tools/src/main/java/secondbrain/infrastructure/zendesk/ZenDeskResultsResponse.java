package secondbrain.infrastructure.zendesk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ZenDeskResultsResponse(String id, String subject, String assignee_id, String submitter_id,
                                     String recipient, String organization_id) {
}
