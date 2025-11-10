package secondbrain.infrastructure.slack.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.apache.commons.lang3.StringUtils;
import secondbrain.domain.data.IdData;
import secondbrain.domain.data.TextData;
import secondbrain.domain.data.UrlData;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SlackSearchResultResource(String id, String timestamp, String channelName, String text,
                                        String permalink) implements TextData, IdData, UrlData {
    @Override
    public String generateId() {
        return id;
    }

    @Override
    public String generateText() {
        return text;
    }

    @Override
    public String generateLinkText() {
        return StringUtils.substring(text()
                        .replaceAll(":.*?:", "")
                        .replaceAll("[^A-Za-z0-9-._ ]", " ")
                        .trim(),
                0, 75);
    }

    @Override
    public String generateUrl() {
        return permalink;
    }
}
