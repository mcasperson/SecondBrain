package secondbrain.infrastructure.zendesk.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ZenDeskTicket(String id, String submitter_id, String assignee_id, String subject,
                            String organization_id) {
}
