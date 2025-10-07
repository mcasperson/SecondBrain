package secondbrain.infrastructure.slack.api;

import org.apache.commons.lang3.StringUtils;
import secondbrain.domain.data.IdData;
import secondbrain.domain.data.TextData;
import secondbrain.domain.data.UrlData;

public record SlackSearchResultResource(String id, String timestamp, String channelName, String text,
                                        String permalink) implements TextData, IdData, UrlData {
    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getText() {
        return text;
    }

    @Override
    public String getLinkText() {
        return StringUtils.substring(text()
                        .replaceAll(":.*?:", "")
                        .replaceAll("[^A-Za-z0-9-._ ]", " ")
                        .trim(),
                0, 75);
    }

    @Override
    public String getUrl() {
        return permalink;
    }
}
