package secondbrain.infrastructure.planhat.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * An individual email. The emails returned when listing the emails of a conversation only populate
 * a subset of these fields, while the email returned when requesting an individual email includes
 * details like the content and the headers.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Email(@JsonProperty("_id") @Nullable String id,
                    @Nullable String conversationId,
                    @Nullable String messageId,
                    @Nullable String threadId,
                    @Nullable String subject,
                    @Nullable String snippet,
                    @Nullable String content,
                    @Nullable String contentType,
                    @Nullable String date,
                    @Nullable String fromAddress,
                    @Nullable String fromName,
                    @Nullable String fromEmail,
                    @Nullable List<String> toAddresses,
                    @Nullable List<String> ccAddresses,
                    @Nullable List<String> bccAddresses,
                    @Nullable String headerMessageId,
                    @Nullable String source,
                    @Nullable String type,
                    @Nullable String userId,
                    @Nullable List<EmailHeader> headers,
                    @Nullable List<EmailAttachment> attachments,
                    @Nullable List<String> companies,
                    @Nullable String url) implements PlanHatActivity {

    public Email updateContentAndSnippet(final String content, final String snippet) {
        return new Email(id, conversationId, messageId, threadId, subject, snippet, content, contentType, date,
                fromAddress, fromName, fromEmail, toAddresses, ccAddresses, bccAddresses, headerMessageId, source,
                type, userId, headers, attachments, companies, url);
    }

    public Email updateUrl(final String url) {
        return new Email(id, conversationId, messageId, threadId, subject, snippet, content, contentType, date,
                fromAddress, fromName, fromEmail, toAddresses, ccAddresses, bccAddresses, headerMessageId, source,
                type, userId, headers, attachments, companies, url);
    }

    @Override
    public String generateId() {
        return getId();
    }

    @Override
    public String generateText() {
        return StringUtils.isBlank(content) ? getSnippet() : getContent();
    }

    @Override
    public String generateLinkText() {
        return "Planhat Email";
    }

    /**
     * PlanHat has no view for an individual email, so emails link to the conversation they belong to.
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

    public String getMessageId() {
        return Objects.requireNonNullElse(messageId, "");
    }

    public String getThreadId() {
        return Objects.requireNonNullElse(threadId, "");
    }

    public String getSubject() {
        return Objects.requireNonNullElse(subject, "");
    }

    public String getSnippet() {
        return Objects.requireNonNullElse(snippet, "");
    }

    public String getContent() {
        return Objects.requireNonNullElse(content, "");
    }

    public String getContentType() {
        return Objects.requireNonNullElse(contentType, "");
    }

    public String getDate() {
        return Objects.requireNonNullElse(date, "");
    }

    public String getFromAddress() {
        return Objects.requireNonNullElse(fromAddress, "");
    }

    public String getFromName() {
        return Objects.requireNonNullElse(fromName, "");
    }

    public String getFromEmail() {
        return Objects.requireNonNullElse(fromEmail, "");
    }

    public List<String> getToAddresses() {
        return Objects.requireNonNullElse(toAddresses, List.of());
    }

    public List<String> getCcAddresses() {
        return Objects.requireNonNullElse(ccAddresses, List.of());
    }

    public List<String> getBccAddresses() {
        return Objects.requireNonNullElse(bccAddresses, List.of());
    }

    public String getHeaderMessageId() {
        return Objects.requireNonNullElse(headerMessageId, "");
    }

    public String getSource() {
        return Objects.requireNonNullElse(source, "");
    }

    public String getType() {
        return Objects.requireNonNullElse(type, "");
    }

    public String getUserId() {
        return Objects.requireNonNullElse(userId, "");
    }

    public List<EmailHeader> getHeaders() {
        return Objects.requireNonNullElse(headers, List.of());
    }

    /**
     * Find the value of an email header. Header names are case-insensitive, so the name is matched
     * without regard to case.
     *
     * @param name The name of the header to find
     * @return The value of the first matching header, or an empty string if there is no such header
     */
    public String getHeader(final String name) {
        return getHeaders().stream()
                .filter(header -> header.getName().equalsIgnoreCase(name))
                .map(EmailHeader::getValue)
                .findFirst()
                .orElse("");
    }

    public List<EmailAttachment> getAttachments() {
        return Objects.requireNonNullElse(attachments, List.of());
    }

    public List<String> getCompanies() {
        return Objects.requireNonNullElse(companies, List.of());
    }

    /**
     * An email is linked to the company that the conversation it belongs to is linked to.
     */
    public String getCompanyId() {
        return getCompanies().stream().findFirst().orElse("");
    }

    public String getUrl() {
        return Objects.requireNonNullElse(url, "");
    }
}
