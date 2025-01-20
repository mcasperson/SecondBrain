package secondbrain.infrastructure.planhat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.commons.lang3.StringUtils;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Conversation(@JsonProperty("_id") String id, String description, String snippet, String date, String companyId) {
    public Conversation updateDescriptionAndSnippet(String description, String snippet) {
        return new Conversation(id, description, snippet, date, companyId);
    }

    public String getContent() {
        return StringUtils.isBlank(description) ? snippet : description;
    }

    public String getPublicUrl(final String url) {
        return url + "/profile/" + companyId() + "?conversationId=" + id();
    }
}
