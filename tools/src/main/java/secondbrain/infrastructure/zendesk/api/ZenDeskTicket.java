package secondbrain.infrastructure.zendesk.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import secondbrain.domain.data.IdData;
import secondbrain.domain.data.TextData;
import secondbrain.domain.data.UrlData;
import secondbrain.domain.tooldefs.MetaObjectResult;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ZenDeskTicket(String id, String submitter_id, String assignee_id, String subject,
                            String organization_id, String recipient, String comments,
                            String url) implements TextData, IdData, UrlData {
    public ZenDeskTicket(final String id, final String subject) {
        this(id, null, null, subject, null, null, null, null);
    }

    public ZenDeskTicket updateComments(final String comments) {
        return new ZenDeskTicket(id, submitter_id, assignee_id, subject, organization_id, recipient, comments, url);
    }

    public ZenDeskTicket updateUrl(final String url) {
        return new ZenDeskTicket(id, submitter_id, assignee_id, subject, organization_id, recipient, comments, url);
    }

    public List<MetaObjectResult> toMetaObjectResult() {
        return List.of(
                new MetaObjectResult("ID", id, id, "ZenDesk"),
                new MetaObjectResult("Subject", subject, id, "ZenDesk"),
                new MetaObjectResult("OrganizationId", organization_id, id, "ZenDesk"),
                new MetaObjectResult("SubmittedId", submitter_id, id, "ZenDesk"),
                new MetaObjectResult("AssigneeId", assignee_id, id, "ZenDesk"));
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getText() {
        return comments;
    }

    @Override
    public String getLinkText() {
        return "ZenDesk Ticket " + id;
    }

    @Override
    public String getUrl() {
        return url;
    }
}
