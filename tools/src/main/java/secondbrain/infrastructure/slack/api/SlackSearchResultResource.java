package secondbrain.infrastructure.slack.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.data.IdData;
import secondbrain.domain.data.TextData;
import secondbrain.domain.data.UrlData;

import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SlackSearchResultResource(@Nullable String id, @Nullable String timestamp,
                                        @Nullable String channelName, @Nullable String text,
                                        @Nullable String permalink) implements TextData, IdData, UrlData {
    @Override
    public String generateId() {
        return Objects.requireNonNullElse(id, "");
    }

    @Override
    public String generateText() {
        return Objects.requireNonNullElse(text, "");
    }

    @Override
    public String generateLinkText() {
        return StringUtils.substring(Objects.requireNonNullElse(text(), "")
                        .replaceAll(":.*?:", "")
                        .replaceAll("[^A-Za-z0-9-._ ]", " ")
                        .trim(),
                0, 75);
    }

    @Override
    public String generateUrl() {
        return Objects.requireNonNullElse(permalink, "");
    }
}
