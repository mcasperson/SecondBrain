package secondbrain.infrastructure.slack;

import com.slack.api.methods.AsyncMethodsClient;
import secondbrain.domain.tools.slack.model.SlackChannelResource;
import secondbrain.domain.tools.slack.model.SlackConversationResource;
import secondbrain.domain.tools.slack.model.SlackSearchResultResource;

import java.util.List;
import java.util.Set;

public interface SlackClient {
    String conversationHistory(
            AsyncMethodsClient client,
            String accessToken,
            String channelId,
            String oldest,
            int ttlSeconds,
            int apiDelay);

    String username(
            AsyncMethodsClient client,
            String accessToken,
            String userId,
            int apiDelay);

    SlackConversationResource channel(
            AsyncMethodsClient client,
            String accessToken,
            String channelId,
            int apiDelay);

    List<SlackSearchResultResource> search(
            AsyncMethodsClient client,
            String accessToken,
            Set<String> keywords,
            int ttlSeconds,
            int apiDelay);

    SlackChannelResource findChannelId(
            AsyncMethodsClient client,
            String accessToken,
            String channel,
            int apiDelay);
}
