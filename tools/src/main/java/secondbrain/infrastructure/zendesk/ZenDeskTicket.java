package secondbrain.infrastructure.zendesk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ZenDeskTicket(String id, String submitter_id, String assignee_id) {
}
