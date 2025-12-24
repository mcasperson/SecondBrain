package secondbrain.infrastructure.zendesk.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.data.IdData;
import secondbrain.domain.data.TextData;
import secondbrain.domain.data.UrlData;
import secondbrain.domain.tooldefs.MetaObjectResult;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ZenDeskTicket(String id,
                            @Nullable @JsonProperty("submitter_id") String submitterId,
                            @Nullable @JsonProperty("assignee_id") String assigneeId,
                            String subject,
                            @Nullable @JsonProperty("organization_id") String organizationId,
                            @Nullable String recipient,
                            @Nullable String comments,
                            @Nullable String url,
                            @Nullable @JsonProperty("created_at") String createdAt) implements TextData, IdData, UrlData {
    public ZenDeskTicket(final String id, final String subject) {
        this(id, null, null, subject, null, null, null, null, null);
    }

    public ZenDeskTicket updateComments(final String comments) {
        return new ZenDeskTicket(id, submitterId, assigneeId, subject, organizationId, recipient, comments, url, createdAt);
    }

    public ZenDeskTicket updateUrl(final String url) {
        return new ZenDeskTicket(id, submitterId, assigneeId, subject, organizationId, recipient, comments, url, createdAt);
    }

    public List<MetaObjectResult> toMetaObjectResult() {
        return List.of(
                new MetaObjectResult("ID", id, id, "ZenDesk"),
                new MetaObjectResult("Subject", subject, id, "ZenDesk"),
                new MetaObjectResult("OrganizationId", organizationId, id, "ZenDesk"),
                new MetaObjectResult("SubmittedId", submitterId, id, "ZenDesk"),
                new MetaObjectResult("AssigneeId", assigneeId, id, "ZenDesk"));
    }

    @Override
    public String generateId() {
        return id;
    }

    @Override
    public String generateText() {
        return comments;
    }

    @Override
    public String generateLinkText() {
        return "ZenDesk Ticket " + id;
    }

    @Override
    public String generateUrl() {
        return url;
    }
}
