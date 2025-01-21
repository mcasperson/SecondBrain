package secondbrain.infrastructure.slack;

import com.slack.api.methods.AsyncMethodsClient;
import com.slack.api.methods.response.conversations.ConversationsListResponse;
import com.slack.api.model.ConversationType;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import secondbrain.domain.tools.slack.ChannelDetails;
import secondbrain.domain.validate.ValidateString;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class SlackClient {
    @Inject
    private ValidateString validateString;

    public Try<ChannelDetails> findChannelId(
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
                .recoverWith(ex -> findChannelId(
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
