package secondbrain.infrastructure.planhat.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.data.IdData;
import secondbrain.domain.data.TextData;
import secondbrain.domain.data.UrlData;

import java.util.List;
import java.util.Objects;

/**
 * An individual message that makes up a ticket conversation. Comments are the public messages
 * exchanged with the customer, while notes are the private messages left by staff.
 * <p>
 * The attachments of a ticket part share the shape of an email attachment, so
 * {@link EmailAttachment} is reused here.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TicketPart(@JsonProperty("_id") @Nullable String id,
                         @Nullable String conversationId,
                         @Nullable String externalTicketId,
                         @Nullable String externalId,
                         @Nullable String type,
                         @Nullable String via,
                         @Nullable String body,
                         @Nullable String createDate,
                         @Nullable String authorName,
                         @Nullable String authorUserId,
                         @Nullable String authorEnduserId,
                         @Nullable String assigneeUserId,
                         @Nullable String source,
                         @JsonProperty("isPrivate") @Nullable Boolean isPrivate,
                         @Nullable Integer days,
                         @Nullable List<String> timeBucket,
                         @Nullable List<EmailAttachment> attachments,
                         @Nullable String companyId,
                         @Nullable String url) implements TextData, IdData, UrlData {

    public TicketPart updateBody(final String body) {
        return new TicketPart(id, conversationId, externalTicketId, externalId, type, via, body, createDate,
                authorName, authorUserId, authorEnduserId, assigneeUserId, source, isPrivate, days, timeBucket,
                attachments, companyId, url);
    }

    /**
     * A ticket part links to the ticket conversation it belongs to, which requires the URL of the
     * PlanHat instance and the company the ticket belongs to. Neither is returned with the part,
     * so both are supplied by the caller.
     */
    public TicketPart updateUrl(final String url, final String companyId) {
        return new TicketPart(id, conversationId, externalTicketId, externalId, type, via, body, createDate,
                authorName, authorUserId, authorEnduserId, assigneeUserId, source, isPrivate, days, timeBucket,
                attachments, companyId, url);
    }

    @Override
    public String generateId() {
        return getId();
    }

    @Override
    public String generateText() {
        return getBody();
    }

    @Override
    public String generateLinkText() {
        return "Planhat Ticket";
    }

    /**
     * PlanHat has no view for an individual ticket part, so parts link to the ticket conversation
     * they belong to.
     */
    @Override
    public String generateUrl() {
        return getUrl() + "/profile/" + getCompanyId() + "?conversationId=" + getConversationId();
    }

    public String getId() {
        return Objects.requireNonNullElse(id, "");
    }

    public String getConversationId() {
        return Objects.requireNonNullElse(conversationId, "");
    }

    public String getExternalTicketId() {
        return Objects.requireNonNullElse(externalTicketId, "");
    }

    public String getExternalId() {
        return Objects.requireNonNullElse(externalId, "");
    }

    public String getType() {
        return Objects.requireNonNullElse(type, "");
    }

    public String getVia() {
        return Objects.requireNonNullElse(via, "");
    }

    public String getBody() {
        return Objects.requireNonNullElse(body, "");
    }

    public String getCreateDate() {
        return Objects.requireNonNullElse(createDate, "");
    }

    public String getAuthorName() {
        return Objects.requireNonNullElse(authorName, "");
    }

    public String getAuthorUserId() {
        return Objects.requireNonNullElse(authorUserId, "");
    }

    public String getAuthorEnduserId() {
        return Objects.requireNonNullElse(authorEnduserId, "");
    }

    public String getAssigneeUserId() {
        return Objects.requireNonNullElse(assigneeUserId, "");
    }

    public String getSource() {
        return Objects.requireNonNullElse(source, "");
    }

    /**
     * Notes left by staff are private, while the comments exchanged with the customer are not.
     */
    public boolean getIsPrivate() {
        return Objects.requireNonNullElse(isPrivate, false);
    }

    public Integer getDays() {
        return Objects.requireNonNullElse(days, 0);
    }

    public List<String> getTimeBucket() {
        return Objects.requireNonNullElse(timeBucket, List.of());
    }

    public List<EmailAttachment> getAttachments() {
        return Objects.requireNonNullElse(attachments, List.of());
    }

    public String getCompanyId() {
        return Objects.requireNonNullElse(companyId, "");
    }

    public String getUrl() {
        return Objects.requireNonNullElse(url, "");
    }
}
