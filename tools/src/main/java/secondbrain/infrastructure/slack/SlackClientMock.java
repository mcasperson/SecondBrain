package secondbrain.infrastructure.slack;

import com.slack.api.methods.AsyncMethodsClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import secondbrain.infrastructure.ollama.OllamaClient;
import secondbrain.infrastructure.slack.api.SlackChannelResource;
import secondbrain.infrastructure.slack.api.SlackConversationResource;
import secondbrain.infrastructure.slack.api.SlackSearchResultResource;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

@ApplicationScoped
public class SlackClientMock implements SlackClient {
    @Inject
    private OllamaClient ollamaClient;

    @Override
    public String conversationHistory(AsyncMethodsClient client, String accessToken, String channelId, String oldest, int ttlSeconds, int apiDelay) {
        return ollamaClient.callOllamaSimple("Write a 5 paragraph Slack conversation history between 3 people discussing the design of a new AI product.");
    }

    @Override
    public String username(AsyncMethodsClient client, String accessToken, String userId, int apiDelay) {
        return ollamaClient.callOllamaSimple("Return a Slack username. You must only return the username, nothing else. You will be penalized for returning anything else.");
    }

    @Override
    public SlackConversationResource channel(AsyncMethodsClient client, String accessToken, String channelId, int apiDelay) {
        final String channelName = ollamaClient.callOllamaSimple("Return a Slack channel name. You must only return the channel name, nothing else. You will be penalized for returning anything else.");

        return new SlackConversationResource(channelName);
    }

    @Override
    public List<SlackSearchResultResource> search(AsyncMethodsClient client, String accessToken, Set<String> keywords, int ttlSeconds, int apiDelay) {
        final String id = ollamaClient.callOllamaSimple("Return a Slack message ID. You must only return the slack message ID, nothing else. You will be penalized for returning anything else.");

        final String channelName = ollamaClient.callOllamaSimple("Return a Slack channel name. You must only return the channel name, nothing else. You will be penalized for returning anything else.");

        final String channelMessage = ollamaClient.callOllamaSimple("Return a single message from a Slack channel.");

        final String url = ollamaClient.callOllamaSimple("Return a URL to a slack channel message.");

        return List.of(new SlackSearchResultResource(
                id,
                ZonedDateTime.now(ZoneId.of("UTC")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")),
                channelName,
                channelMessage,
                url));
    }

    @Override
    public SlackChannelResource findChannelId(AsyncMethodsClient client, String accessToken, String channel, int apiDelay) {
        final String channelId = ollamaClient.callOllamaSimple("Return a Slack channel ID. You must only return the channel ID, nothing else. You will be penalized for returning anything else.");

        final String teamId = ollamaClient.callOllamaSimple("Return a Slack team ID. You must only return the team ID, nothing else. You will be penalized for returning anything else.");

        return new SlackChannelResource(teamId, channelId, channel);
    }
}
