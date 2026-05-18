package secondbrain.infrastructure.slack.api;

import com.slack.api.methods.response.conversations.ConversationsHistoryResponse;
import com.slack.api.methods.response.conversations.ConversationsRepliesResponse;

import java.util.Map;

/**
 * Combines a Slack channel conversation history with all fetched thread replies,
 * keyed by the thread root message timestamp.
 */
public record SlackChannelWithReplies(
        ConversationsHistoryResponse history,
        Map<String, ConversationsRepliesResponse> repliesByThreadTs) {
}
