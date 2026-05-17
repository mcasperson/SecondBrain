package secondbrain.infrastructure.planhat.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.data.IdData;
import secondbrain.domain.data.TextData;
import secondbrain.domain.data.UrlData;

import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Conversation(@JsonProperty("_id") @Nullable String id,
                           @Nullable String description,
                           @Nullable String snippet,
                           @Nullable String date,
                           @Nullable String companyId,
                           @Nullable String companyName,
                           @Nullable String subject,
                           @Nullable String type,
                           @Nullable String url) implements TextData, IdData, UrlData {
    public Conversation updateDescriptionAndSnippet(final String description, final String snippet) {
        return new Conversation(id, description, snippet, date, companyId, companyName, subject, type, url);
    }

    public Conversation updateUrl(final String url) {
        return new Conversation(id, description, snippet, date, companyId, companyName, subject, type, url);
    }

    @Override
    public String generateId() {
        return Objects.requireNonNullElse(id, "");
    }

    @Override
    public String generateText() {
        return StringUtils.isBlank(description) ? getSnippet() : getDescription();
    }

    @Override
    public String generateLinkText() {
        return "Planhat Conversation";
    }

    @Override
    public String generateUrl() {
        return getUrl() + "/profile/" + getCompanyId() + "?conversationId=" + getId();
    }

    public String getDescription() {
        return Objects.requireNonNullElse(description, "");
    }

    public String getSnippet() {
        return Objects.requireNonNullElse(snippet, "");
    }

    public String getId() {
        return Objects.requireNonNullElse(id, "");
    }

    public String getDate() {
        return Objects.requireNonNullElse(date, "");
    }

    public String getCompanyId() {
        return Objects.requireNonNullElse(companyId, "");
    }

    public String getCompanyName() {
        return Objects.requireNonNullElse(companyName, "");
    }

    public String getSubject() {
        return Objects.requireNonNullElse(subject, "");
    }

    public String getType() {
        return Objects.requireNonNullElse(type, "");
    }

    public String getUrl() {
        return Objects.requireNonNullElse(url, "");
    }
}
