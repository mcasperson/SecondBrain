package secondbrain.infrastructure.slack;

import com.slack.api.methods.AsyncMethodsClient;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.response.conversations.ConversationsHistoryResponse;
import com.slack.api.methods.response.conversations.ConversationsListResponse;
import com.slack.api.methods.response.search.SearchAllResponse;
import com.slack.api.model.ConversationType;
import io.vavr.API;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.codec.digest.DigestUtils;
import secondbrain.domain.concurrency.SemaphoreLender;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.exceptions.EmptyString;
import secondbrain.domain.exceptions.ExternalFailure;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.tools.slack.ChannelDetails;
import secondbrain.domain.validate.ValidateString;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

import static io.vavr.Predicates.instanceOf;

@ApplicationScoped
public class SlackClient {

    private static final int RETRIES = 5;
    private static final int RETRY_DELAY = 30000;
    private static final int RETRY_JITTER = 1000;
    private static final SemaphoreLender SEMAPHORE_LENDER = new SemaphoreLender(Constants.DEFAULT_SEMAPHORE_COUNT);

    @Inject
    private ValidateString validateString;

    @Inject
    private LocalStorage localStorage;

    @Inject
    private Logger logger;

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
        final String hash = DigestUtils.sha256Hex(channelId + oldest);

        return Try.withResources(SEMAPHORE_LENDER::lend)
                .of(sem -> Try
                        .of(() -> localStorage.getOrPutObject(
                                SlackClient.class.getSimpleName(),
                                "SlackAPIConversationHistory",
                                hash,
                                ttlSeconds,
                                ConversationsHistoryResponse.class,
                                () -> conversationHistoryFromApi(client, accessToken, channelId, oldest)))


                        .get())
                .get();
    }

    private ConversationsHistoryResponse conversationHistoryFromApi(
            final AsyncMethodsClient client,
            final String accessToken,
            final String channelId,
            final String oldest) {
        return Try.of(() -> client.conversationsHistory(r -> r
                                .token(accessToken)
                                .channel(channelId)
                                .oldest(oldest))
                        .get())
                .mapFailure(API.Case(API.$(), ex -> new ExternalFailure("Could not call conversationsHistory", ex)))
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
        final String hash = DigestUtils.sha256Hex(String.join(" ", keywords));

        return Try.withResources(SEMAPHORE_LENDER::lend)
                .of(sem -> Try
                        .of(() -> localStorage.getOrPutObject(
                                SlackClient.class.getSimpleName(),
                                "SlackAPISearch",
                                hash,
                                ttlSeconds,
                                SearchAllResponse.class,
                                () -> searchFromApi(client, accessToken, keywords)))
                        .get())
                .get();
    }

    private SearchAllResponse searchFromApi(
            final AsyncMethodsClient client,
            final String accessToken,
            final Set<String> keywords) {
        return searchFromApi(client, accessToken, keywords, 0);
    }

    private SearchAllResponse searchFromApi(
            final AsyncMethodsClient client,
            final String accessToken,
            final Set<String> keywords,
            final int retryCount) {

        if (retryCount > RETRIES) {
            throw new ExternalFailure("Could not call searchAll after " + RETRIES + " retries");
        }

        if (retryCount > 0) {
            logger.info("Retrying Slack searchAll");
            Try.run(() -> Thread.sleep(RETRY_DELAY + (int) (Math.random() * RETRY_JITTER)));
        }

        final Try<SearchAllResponse> result = Try
                .of(() -> client.searchAll(r -> r.token(accessToken)
                                .query(String.join(" ", keywords)))
                        .get())
                .recover(SlackApiException.class, ex -> {
                    if (ex.getResponse().code() == 429) {
                        return searchFromApi(client, accessToken, keywords, retryCount + 1);
                    }

                    throw new ExternalFailure("Could not call searchAll", ex);
                });

        return result
                .mapFailure(API.Case(API.$(instanceOf(ExternalFailure.class)), ex -> ex))
                .mapFailure(API.Case(API.$(), ex -> new ExternalFailure("Could not call searchAll", ex)))
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
        final String hash = DigestUtils.sha256Hex(channel);

        // get the result from the cache
        return Try.withResources(SEMAPHORE_LENDER::lend)
                .of(sem -> Try
                        .of(() -> localStorage.getOrPutObject(
                                SlackClient.class.getSimpleName(),
                                "SlackAPIChannel",
                                hash,
                                ChannelDetails.class,
                                () -> findChannelIdFromApi(client, accessToken, channel, null)))
                        .get())
                .get();
    }

    private ChannelDetails findChannelIdFromApi(
            final AsyncMethodsClient client,
            final String accessToken,
            final String channel,
            final String cursor) {

        final ConversationsListResponse response = findConversationListFromApi(client, accessToken, cursor, 0);

        final Try<ChannelDetails> results = Try.of(() -> response)
                // try to get the channel
                .map(r -> getChannelId(r, channel))
                // this fails if nothing was returned
                .map(Optional::get)
                // if we fail, we try to get the next page
                .recover(NoSuchElementException.class, ex -> findChannelIdFromApi(
                        client,
                        accessToken,
                        channel,
                        // the cursor must be a non-empty string to do a recursive call
                        validateString.throwIfEmpty(response.getResponseMetadata().getNextCursor())));

        return results
                .mapFailure(
                        API.Case(API.$(instanceOf(EmptyString.class)),
                                ex -> new InternalFailure("Slack channel API reached the end of the results. Failed to find channel " + channel, ex)))
                .mapFailure(API.Case(API.$(), ex -> new ExternalFailure("Could not call conversationsList", ex)))
                .get();
    }

    private Optional<ChannelDetails> getChannelId(final ConversationsListResponse response, final String channel) {
        return response.getChannels()
                .stream()
                .filter(c -> c.getName().equals(channel))
                .map(c -> new ChannelDetails(channel, c.getId(), c.getContextTeamId()))
                .findFirst();
    }

    private ConversationsListResponse findConversationListFromApi(
            final AsyncMethodsClient client,
            final String accessToken,
            final String cursor,
            final int retryCount) {

        if (retryCount > RETRIES) {
            throw new ExternalFailure("Could not call conversationsList after " + RETRIES + " retries");
        }

        if (retryCount > 0) {
            logger.info("Retrying Slack conversationsList");
            Try.run(() -> Thread.sleep(RETRY_DELAY + (int) (Math.random() * RETRY_JITTER)));
        }

        return Try.of(() -> client.conversationsList(r -> r
                        .token(accessToken)
                        .limit(1000)
                        .types(List.of(ConversationType.PUBLIC_CHANNEL))
                        .excludeArchived(true)
                        .cursor(cursor)).get())
                .recover(SlackApiException.class, ex -> {
                    if (ex.getResponse().code() == 429) {
                        return findConversationListFromApi(client, accessToken, cursor, retryCount + 1);
                    }

                    throw new ExternalFailure("Could not call conversationsList", ex);
                })
                .get();
    }
}
