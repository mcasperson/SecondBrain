package secondbrain.infrastructure.slack;

import com.slack.api.methods.AsyncMethodsClient;
import com.slack.api.methods.response.conversations.ConversationsHistoryResponse;
import com.slack.api.methods.response.conversations.ConversationsListResponse;
import com.slack.api.methods.response.search.SearchAllResponse;
import com.slack.api.methods.response.team.TeamInfoResponse;
import com.slack.api.model.ConversationType;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.codec.digest.DigestUtils;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.tools.slack.ChannelDetails;
import secondbrain.domain.validate.ValidateString;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@ApplicationScoped
public class SlackClient {

    @Inject
    private ValidateString validateString;

    @Inject
    private LocalStorage localStorage;

    public ConversationsHistoryResponse conversationHistory(
            final AsyncMethodsClient client,
            final String accessToken,
            final String channelId,
            final String oldest,
            final int ttlSeconds) {

        /*
            The Slack API enforces a lot of API rate limits. So we will cache the results of a channel lookup
            based on a hash of the channel name and the access token.
         */
        final TeamInfoResponse team = teamFromApi(client, accessToken, Set.of());
        final String hash = DigestUtils.sha256Hex(team.getTeam().getId() + channelId + oldest);

        return localStorage.getOrPutObject(
                SlackClient.class.getSimpleName(),
                "SlackAPIConversationHistory",
                hash,
                ttlSeconds,
                ConversationsHistoryResponse.class,
                () -> conversationHistoryFromApi(client, accessToken, channelId, oldest));
    }

    private ConversationsHistoryResponse conversationHistoryFromApi(
            final AsyncMethodsClient client,
            final String accessToken,
            final String channelId,
            final String oldest) {
        return Try.of(() -> client.conversationsHistory(r -> r
                        .token(accessToken)
                        .channel(channelId)
                        .oldest(oldest)).get())
                .get();
    }

    public SearchAllResponse search(
            final AsyncMethodsClient client,
            final String accessToken,
            final Set<String> keywords,
            final int ttlSeconds) {

        /*
            The Slack API enforces a lot of API rate limits. So we will cache the results of a channel lookup
            based on a hash of the channel name and the access token.
         */
        final TeamInfoResponse team = teamFromApi(client, accessToken, Set.of());
        final String hash = DigestUtils.sha256Hex(team.getTeam().getId() + String.join(" ", keywords));

        return localStorage.getOrPutObject(
                SlackClient.class.getSimpleName(),
                "SlackAPISearch",
                hash,
                ttlSeconds,
                SearchAllResponse.class,
                () -> searchFromApi(client, accessToken, keywords));
    }

    private SearchAllResponse searchFromApi(
            final AsyncMethodsClient client,
            final String accessToken,
            final Set<String> keywords) {
        return Try.of(() -> client.searchAll(r -> r.token(accessToken)
                                .query(String.join(" ", keywords)))
                        .get())
                .get();
    }

    private TeamInfoResponse teamFromApi(
            final AsyncMethodsClient client,
            final String accessToken,
            final Set<String> keywords) {
        return Try.of(() -> client.teamInfo(r -> r.token(accessToken))
                        .get())
                .get();
    }

    public ChannelDetails findChannelId(
            final AsyncMethodsClient client,
            final String accessToken,
            final String channel) {

        /*
            The Slack API enforces a lot of API rate limits. So we will cache the results of a channel lookup
            based on a hash of the channel name and the access token.
         */
        final TeamInfoResponse team = teamFromApi(client, accessToken, Set.of());
        final String hash = DigestUtils.sha256Hex(team.getTeam().getId() + channel);

        // get the result from the cache
        return localStorage.getOrPutObject(
                SlackClient.class.getSimpleName(),
                "SlackAPI",
                hash,
                ChannelDetails.class,
                () -> findChannelIdFromApi(client, accessToken, channel, null));
    }

    private ChannelDetails findChannelIdFromApi(
            final AsyncMethodsClient client,
            final String accessToken,
            final String channel,
            final String cursor) {

        final Try<ConversationsListResponse> response = Try.of(() -> client.conversationsList(r -> r
                .token(accessToken)
                .limit(1000)
                .types(List.of(ConversationType.PUBLIC_CHANNEL))
                .excludeArchived(true)
                .cursor(cursor)).get());

        return response
                // try to get the channel
                .map(r -> getChannelId(r, channel))
                // this fails if nothing was returned
                .map(Optional::get)
                // if we fail, we try to get the next page
                .recover(ex -> findChannelIdFromApi(
                        client,
                        accessToken,
                        channel,
                        // the cursor must be a non-empty string to do a recursive call
                        validateString.throwIfEmpty(response.get().getResponseMetadata().getNextCursor())))
                .get();
    }

    private Optional<ChannelDetails> getChannelId(final ConversationsListResponse response, final String channel) {
        return response.getChannels()
                .stream()
                .filter(c -> c.getName().equals(channel))
                .map(c -> new ChannelDetails(channel, c.getId(), c.getContextTeamId()))
                .findFirst();
    }
}
