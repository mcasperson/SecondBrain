package secondbrain.infrastructure.slack.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import secondbrain.domain.data.IdData;
import secondbrain.domain.data.TextData;
import secondbrain.domain.data.UrlData;

import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SlackChannelResource(String teamId, String channelId, String channelName,
                                   String conversation) implements UrlData, IdData, TextData {
    public SlackChannelResource(String teamId, String channelId, String channelName) {
        this(teamId, channelId, channelName, null);
    }

    @Override
    public String getLinkText() {
        return "Slack " + channelName();
    }

    @Override
    public String getUrl() {
        return "https://app.slack.com/client/" + teamId() + "/" + channelId();
    }

    @Override
    public String getId() {
        return channelId;
    }

    @Override
    public String getText() {
        return Objects.requireNonNull(conversation, "");
    }

    public SlackChannelResource updateConversation(final String conversation) {
        return new SlackChannelResource(teamId, channelId, channelName, conversation);
    }
}
