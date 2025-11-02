package secondbrain.infrastructure.slack.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SlackConversationResource(String channelName) {
}
