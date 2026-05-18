package secondbrain.infrastructure.slack.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.slack.api.methods.response.conversations.ConversationsHistoryResponse;
import com.slack.api.methods.response.conversations.ConversationsRepliesResponse;

import java.util.Map;

/**
 * Combines a Slack channel conversation history with all fetched thread replies,
 * keyed by the thread root message timestamp.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SlackChannelWithReplies(
        ConversationsHistoryResponse history,
        Map<String, ConversationsRepliesResponse> repliesByThreadTs) {
}
