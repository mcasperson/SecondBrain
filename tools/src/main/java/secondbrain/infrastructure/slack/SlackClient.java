package secondbrain.infrastructure.slack;

import com.slack.api.methods.AsyncMethodsClient;
import com.slack.api.methods.response.conversations.ConversationsListResponse;
import com.slack.api.model.ConversationType;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.codec.digest.DigestUtils;
import secondbrain.domain.json.JsonDeserializer;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.tools.slack.ChannelDetails;
import secondbrain.domain.validate.ValidateString;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class SlackClient {
    private static final String SALT = "YrZqGXwuNKEeWRN1sTA9";

    @Inject
    private ValidateString validateString;

    @Inject
    private LocalStorage localStorage;

    @Inject
    private JsonDeserializer jsonDeserializer;

    public Try<ChannelDetails> findChannelId(
            final AsyncMethodsClient client,
            final String accessToken,
            final String channel) {

        /*
            The Slack API enforces a lot of API rate limits. So we will cache the results of a channel lookup
            based on a hash of the channel name and the access token.
         */
        final String hash = DigestUtils.sha256Hex(accessToken + channel + SALT);

        // get the result from the cache
        return Try.of(() -> localStorage.getString(SlackClient.class.getSimpleName(), "SlackAPI", hash))
                // a cache miss means the string is empty, so we throw an exception
                .map(validateString::throwIfEmpty)
                // a cache hit means we deserialize the result
                .mapTry(r -> jsonDeserializer.deserialize(r, ChannelDetails.class))
                // a cache miss means we call the API and then save the result in the cache
                .recoverWith(ex -> findChannelIdFromApi(client, accessToken, channel, cursor)
                        .onSuccess(r -> localStorage.putString(
                                SlackClient.class.getSimpleName(),
                                "SlackAPI",
                                hash,
                                jsonDeserializer.serialize(r))));

    }

    private Try<ChannelDetails> findChannelIdFromApi(
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
                .recoverWith(ex -> findChannelIdFromApi(
                        client,
                        accessToken,
                        channel,
                        // the cursor must be a non-empty string to do a recursive call
                        validateString.throwIfEmpty(response.get().getResponseMetadata().getNextCursor())));
    }

    private Optional<ChannelDetails> getChannelId(final ConversationsListResponse response, final String channel) {
        return response.getChannels()
                .stream()
                .filter(c -> c.getName().equals(channel))
                .map(c -> new ChannelDetails(channel, c.getId(), c.getContextTeamId()))
                .findFirst();
    }
}
