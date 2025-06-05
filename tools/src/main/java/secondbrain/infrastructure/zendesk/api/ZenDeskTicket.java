package secondbrain.infrastructure.zendesk.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import secondbrain.domain.tooldefs.MetaObjectResult;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ZenDeskTicket(String id, String submitter_id, String assignee_id, String subject,
                            String organization_id, String recipient) {
    public ZenDeskTicket(final String id, final String subject) {
        this(id, null, null, subject, null, null);
    }

    public List<MetaObjectResult> toMetaObjectResult() {
        return List.of(
                new MetaObjectResult("ID", id),
                new MetaObjectResult("Subject", subject),
                new MetaObjectResult("OrganizationId", organization_id),
                new MetaObjectResult("SubmittedId", submitter_id),
                new MetaObjectResult("AssigneeId", assignee_id));
    }
}
