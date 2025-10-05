package secondbrain.infrastructure.planhat.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.commons.lang3.StringUtils;
import secondbrain.domain.data.IdData;
import secondbrain.domain.data.TextData;
import secondbrain.domain.data.UrlData;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Conversation(@JsonProperty("_id") String id,
                           String description,
                           String snippet,
                           String date,
                           String companyId,
                           String companyName,
                           String subject,
                           String type,
                           String url) implements TextData, IdData, UrlData {
    public Conversation updateDescriptionAndSnippet(final String description, final String snippet) {
        return new Conversation(id, description, snippet, date, companyId, companyName, subject, type, url);
    }

    public Conversation updateUrl(final String url) {
        return new Conversation(id, description, snippet, date, companyId, companyName, subject, type, url);
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getText() {
        return StringUtils.isBlank(description) ? snippet : description;
    }

    @Override
    public String getLinkText() {
        return "Planhat Conversation";
    }

    @Override
    public String getUrl() {
        return url + "/profile/" + companyId() + "?conversationId=" + id();
    }
}
