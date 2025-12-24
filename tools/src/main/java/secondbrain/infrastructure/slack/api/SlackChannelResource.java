package secondbrain.infrastructure.slack.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.data.IdData;
import secondbrain.domain.data.TextData;
import secondbrain.domain.data.UrlData;

import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SlackChannelResource(String teamId, String channelId, String channelName,
                                   @Nullable String conversation) implements UrlData, IdData, TextData {
    public SlackChannelResource(String teamId, String channelId, String channelName) {
        this(teamId, channelId, channelName, null);
    }

    @Override
    public String generateLinkText() {
        return "Slack " + channelName();
    }

    @Override
    public String generateUrl() {
        return "https://app.slack.com/client/" + teamId() + "/" + channelId();
    }

    @Override
    public String generateId() {
        return channelId;
    }

    @Override
    public String generateText() {
        return Objects.requireNonNull(conversation, "");
    }

    public SlackChannelResource updateConversation(final String conversation) {
        return new SlackChannelResource(teamId, channelId, channelName, conversation);
    }
}
