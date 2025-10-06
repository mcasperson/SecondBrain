package secondbrain.infrastructure.slack;

import com.google.common.util.concurrent.RateLimiter;
import com.slack.api.methods.AsyncMethodsClient;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.response.conversations.ConversationsHistoryResponse;
import com.slack.api.methods.response.conversations.ConversationsInfoResponse;
import com.slack.api.methods.response.conversations.ConversationsListResponse;
import com.slack.api.methods.response.search.SearchAllResponse;
import com.slack.api.methods.response.users.UsersInfoResponse;
import com.slack.api.model.Conversation;
import com.slack.api.model.ConversationType;
import com.slack.api.model.Message;
import com.slack.api.model.User;
import io.vavr.API;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.codec.digest.DigestUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.exceptions.EmptyString;
import secondbrain.domain.exceptions.ExternalFailure;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.mutex.Mutex;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.tools.slack.ChannelDetails;
import secondbrain.domain.validate.ValidateString;
import secondbrain.infrastructure.slack.api.SlackChannelResource;
import secondbrain.infrastructure.slack.api.SlackConversationResource;
import secondbrain.infrastructure.slack.api.SlackSearchResultResource;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

import static io.vavr.Predicates.instanceOf;

@ApplicationScoped
public class SlackClientLive implements SlackClient {

    private static final int RETRIES = 10;
    private static final int RETRY_JITTER = 10000;
    private static final RateLimiter RATE_LIMITER = RateLimiter.create(1);
    private static final long MUTEX_TIMEOUT_MS = 30 * 60 * 1000;

    @Inject
    @ConfigProperty(name = "sb.slack.lock", defaultValue = "sb_slack.lock")
    private String lockFile;

    @Inject
    private ValidateString validateString;

    @Inject
    private LocalStorage localStorage;

    @Inject
    private Logger logger;

    @Inject
    private Mutex mutex;

    @Override
    public String conversationHistory(
            final AsyncMethodsClient client,
            final String accessToken,
            final String channelId,
            final String oldest,
            final int ttlSeconds,
            final int apiDelay) {
        /*
            The Slack API enforces a lot of API rate limits. So we will cache the results of a channel lookup
            based on a hash of the channel name and the access token.
         */
        final String hash = DigestUtils.sha256Hex(channelId + oldest);

        return Try
                .of(() -> localStorage.getOrPutObject(
                                SlackClientLive.class.getSimpleName(),
                                "SlackAPIConversationHistory",
                                hash,
                                ttlSeconds,
                                ConversationsHistoryResponse.class,
                                () -> conversationHistoryFromApi(client, accessToken, channelId, oldest, 0, apiDelay))
                        .result())
                .map(this::conversationsToText)
                .get();
    }

    private String conversationsToText(final ConversationsHistoryResponse conversation) {
        return conversation.getMessages()
                .stream()
                .map(Message::getText)
                .reduce("", (a, b) -> a + "\n" + b);
    }

    private ConversationsHistoryResponse conversationHistoryFromApi(
            final AsyncMethodsClient client,
            final String accessToken,
            final String channelId,
            final String oldest,
            final int retryCount,
            final int apiDelay) {
        if (retryCount > RETRIES) {
            throw new InternalFailure("Could not call conversationsHistory after " + RETRIES + " retries");
        }

        RATE_LIMITER.acquire();

        if (retryCount > 0) {
            logger.info("Retrying Slack conversationsHistory");
            Try.run(() -> Thread.sleep(apiDelay + (int) (Math.random() * RETRY_JITTER)));
        }

        final Try<ConversationsHistoryResponse> result = Try.of(() -> client.conversationsHistory(r -> r
                                .token(accessToken)
                                .channel(channelId)
                                .oldest(oldest))
                        .whenComplete((r, ex) -> {
                            if (ex != null) {
                                logger.warning("Failed to call Slack conversationsHistory");
                            }
                        })
                        .get())
                .recover(SlackApiException.class, ex -> {
                    if (ex.getResponse().code() == 429) {
                        return conversationHistoryFromApi(client, accessToken, channelId, oldest, retryCount + 1, apiDelay);
                    }

                    throw new ExternalFailure("Could not call searchAll", ex);
                });

        return result
                .mapFailure(API.Case(API.$(), ex -> new ExternalFailure("Could not call conversationsHistory", ex)))
                .get();
    }

    @Override
    public String username(
            final AsyncMethodsClient client,
            final String accessToken,
            final String userId,
            final int apiDelay) {
        /*
            The Slack API enforces a lot of API rate limits. So we will cache the results of a channel lookup
            based on a hash of the channel name and the access token.
         */
        final String hash = DigestUtils.sha256Hex(userId);

        return Try
                .of(() -> localStorage.getOrPutObject(
                                SlackClientLive.class.getSimpleName(),
                                "SlackAPIUserInfo",
                                hash,
                                UsersInfoResponse.class,
                                () -> userFromApi(client, accessToken, userId, 0, apiDelay))
                        .result())
                .map(u -> u.getUser().getName())
                .get();
    }

    private UsersInfoResponse userFromApi(
            final AsyncMethodsClient client,
            final String accessToken,
            final String userId,
            final int retryCount,
            final int apiDelay) {
        return mutex.acquire(
                MUTEX_TIMEOUT_MS,
                lockFile + ".users",
                () -> userFromApiLocked(client, accessToken, userId, retryCount, apiDelay));
    }

    private UsersInfoResponse userFromApiLocked(
            final AsyncMethodsClient client,
            final String accessToken,
            final String userId,
            final int retryCount,
            final int apiDelay) {
        if (retryCount > RETRIES) {
            throw new InternalFailure("Could not call usersInfo after " + RETRIES + " retries");
        }

        RATE_LIMITER.acquire();

        if (retryCount > 0) {
            logger.info("Retrying Slack usersInfo");
            Try.run(() -> Thread.sleep(apiDelay + (int) (Math.random() * RETRY_JITTER)));
        }

        return Try.of(() -> client.usersInfo(r -> r.token(accessToken).user(userId))
                        .whenComplete((r, ex) -> {
                            if (ex != null) {
                                logger.warning("Failed to call Slack usersInfo");
                            }
                        })
                        .get())
                .recover(SlackApiException.class, ex -> {
                    if (ex.getResponse().code() == 429) {
                        return userFromApi(client, accessToken, userId, retryCount + 1, apiDelay);
                    }

                    throw new ExternalFailure("Could not call usersInfo", ex);
                })
                .recover(ex -> {
                    // Just return an empty user if we can't find the user
                    final UsersInfoResponse userInfo = new UsersInfoResponse();
                    final User user = new User();
                    user.setName("Unknown user");
                    userInfo.setUser(user);
                    return userInfo;
                })
                .get();
    }

    public SlackConversationResource channel(
            final AsyncMethodsClient client,
            final String accessToken,
            final String channelId,
            final int apiDelay) {
        /*
            The Slack API enforces a lot of API rate limits. So we will cache the results of a channel lookup
            based on a hash of the channel name and the access token.
         */
        final String hash = DigestUtils.sha256Hex(channelId);

        return Try
                .of(() -> localStorage.getOrPutObject(
                                SlackClientLive.class.getSimpleName(),
                                "SlackAPIConversationsInfo",
                                hash,
                                ConversationsInfoResponse.class,
                                () -> channelFromApi(client, accessToken, channelId, 0, apiDelay))
                        .result())
                .map(c -> new SlackConversationResource(c.getChannel().getName()))
                .get();
    }

    private ConversationsInfoResponse channelFromApi(
            final AsyncMethodsClient client,
            final String accessToken,
            final String channelId,
            final int retryCount,
            final int apiDelay) {
        return mutex.acquire(
                MUTEX_TIMEOUT_MS,
                lockFile + ".channel",
                () -> channelFromApiLocked(client, accessToken, channelId, retryCount, apiDelay));
    }

    private ConversationsInfoResponse channelFromApiLocked(
            final AsyncMethodsClient client,
            final String accessToken,
            final String channelId,
            final int retryCount,
            final int apiDelay) {
        if (retryCount > RETRIES) {
            throw new InternalFailure("Could not call channel after " + RETRIES + " retries");
        }

        RATE_LIMITER.acquire();

        if (retryCount > 0) {
            logger.info("Retrying Slack channel");
            Try.run(() -> Thread.sleep(apiDelay + (int) (Math.random() * RETRY_JITTER)));
        }

        return Try.of(() -> client.conversationsInfo(r -> r.token(accessToken).channel(channelId))
                        .whenComplete((r, ex) -> {
                            if (ex != null) {
                                logger.warning("Failed to call Slack channel");
                            }
                        })
                        .get())
                .recover(SlackApiException.class, ex -> {
                    if (ex.getResponse().code() == 429) {
                        return channelFromApi(client, accessToken, channelId, retryCount + 1, apiDelay);
                    }

                    throw new ExternalFailure("Could not call usersInfo", ex);
                })
                .recover(ex -> {
                    // Just return an empty user if we can't find the channel
                    final ConversationsInfoResponse conversationsInfo = new ConversationsInfoResponse();
                    final Conversation conversation = new Conversation();
                    conversation.setName("Unknown channel");
                    conversationsInfo.setChannel(conversation);
                    return conversationsInfo;
                })
                .get();
    }

    @Override
    public List<SlackSearchResultResource> search(
            final AsyncMethodsClient client,
            final String accessToken,
            final Set<String> keywords,
            final int ttlSeconds,
            final int apiDelay) {
        /*
            The Slack API enforces a lot of API rate limits. So we will cache the results of a channel lookup
            based on a hash of the channel name and the access token.
         */
        final String hash = DigestUtils.sha256Hex(String.join(" ", keywords));

        return Try
                .of(() -> localStorage.getOrPutObject(
                                SlackClientLive.class.getSimpleName(),
                                "SlackAPISearch",
                                hash,
                                ttlSeconds,
                                SearchAllResponse.class,
                                () -> searchFromApi(client, accessToken, keywords, apiDelay))
                        .result())
                .map(s -> s.getMessages()
                        .getMatches()
                        .stream()
                        .map(m -> new SlackSearchResultResource(
                                m.getId(),
                                m.getTs(),
                                m.getChannel().getName(),
                                m.getText(),
                                m.getPermalink()))
                        .toList())
                .get();
    }

    private SearchAllResponse searchFromApi(
            final AsyncMethodsClient client,
            final String accessToken,
            final Set<String> keywords,
            final int apiDelay) {
        return mutex.acquire(
                MUTEX_TIMEOUT_MS,
                lockFile + " .search",
                () -> searchFromApiLocked(client, accessToken, keywords, 0, apiDelay));
    }

    private SearchAllResponse searchFromApiLocked(
            final AsyncMethodsClient client,
            final String accessToken,
            final Set<String> keywords,
            final int retryCount,
            final int apiDelay) {

        if (retryCount > RETRIES) {
            throw new InternalFailure("Could not call searchAll after " + RETRIES + " retries");
        }

        RATE_LIMITER.acquire();

        if (retryCount > 0) {
            logger.info("Retrying Slack searchAll");
            Try.run(() -> Thread.sleep(apiDelay + (int) (Math.random() * RETRY_JITTER)));
        }

        final Try<SearchAllResponse> result = Try
                .of(() -> client.searchAll(r -> r.token(accessToken)
                                .query(String.join(" ", keywords)))
                        .whenComplete((r, ex) -> {
                            if (ex != null) {
                                logger.warning("Failed to call Slack searchAll");
                            }
                        })
                        .get())
                .recover(ex -> searchFromApiLocked(client, accessToken, keywords, retryCount + 1, apiDelay));

        return result
                .mapFailure(API.Case(API.$(instanceOf(ExternalFailure.class)), ex -> ex))
                .mapFailure(API.Case(API.$(), ex -> new ExternalFailure("Could not call searchAll", ex)))
                .get();
    }

    @Override
    public SlackChannelResource findChannelId(
            final AsyncMethodsClient client,
            final String accessToken,
            final String channel,
            final int apiDelay) {

        /*
            The Slack API enforces a lot of API rate limits. So we will cache the results of a channel lookup
            based on a hash of the channel name and the access token.
         */
        final String hash = DigestUtils.sha256Hex(channel);

        // get the result from the cache
        return Try
                .of(() -> localStorage.getOrPutObject(
                        SlackClientLive.class.getSimpleName(),
                        "SlackAPIChannel",
                        hash,
                        ChannelDetails.class,
                        () -> findChannelIdFromApi(client, accessToken, channel, null, apiDelay)).result())
                .map(c -> new SlackChannelResource(c.teamId(), c.channelId(), c.channelName()))
                .get();
    }

    private ChannelDetails findChannelIdFromApi(
            final AsyncMethodsClient client,
            final String accessToken,
            final String channel,
            final String cursor,
            final int apiDelay) {
        return mutex.acquire(
                MUTEX_TIMEOUT_MS,
                lockFile + ".channelid",
                () -> findChannelIdFromApiLocked(client, accessToken, channel, cursor, apiDelay));
    }

    private ChannelDetails findChannelIdFromApiLocked(
            final AsyncMethodsClient client,
            final String accessToken,
            final String channel,
            final String cursor,
            final int apiDelay) {

        final ConversationsListResponse response = findConversationListFromApi(client, accessToken, cursor, 0, apiDelay);

        final Try<ChannelDetails> results = Try.of(() -> response)
                // try to get the channel
                .map(r -> getChannelId(r, channel))
                // this fails if nothing was returned
                .map(Optional::get)
                // if we fail, we try to get the next page
                .recover(NoSuchElementException.class, ex -> findChannelIdFromApiLocked(
                        client,
                        accessToken,
                        channel,
                        // the cursor must be a non-empty string to do a recursive call
                        validateString.throwIfEmpty(response.getResponseMetadata().getNextCursor()),
                        apiDelay));

        return results
                .mapFailure(
                        API.Case(API.$(instanceOf(EmptyString.class)),
                                ex -> new InternalFailure("Slack channel API reached the end of the results. Failed to find channel " + channel, ex)))
                .mapFailure(API.Case(API.$(), ex -> new ExternalFailure("Could not call conversationsList", ex)))
                .get();
    }

    private ConversationsListResponse findConversationListFromApi(
            final AsyncMethodsClient client,
            final String accessToken,
            final String cursor,
            final int retryCount,
            final int apiDelay) {
        return mutex.acquire(
                MUTEX_TIMEOUT_MS,
                lockFile + ".conversationlist",
                () -> findConversationListFromApiLocked(client, accessToken, cursor, retryCount, apiDelay));
    }

    private ConversationsListResponse findConversationListFromApiLocked(
            final AsyncMethodsClient client,
            final String accessToken,
            final String cursor,
            final int retryCount,
            final int apiDelay) {

        if (retryCount > RETRIES) {
            throw new InternalFailure("Could not call conversationsList after " + RETRIES + " retries");
        }

        RATE_LIMITER.acquire();

        if (retryCount > 0) {
            logger.info("Retrying Slack conversationsList");
            Try.run(() -> Thread.sleep(apiDelay + (int) (Math.random() * RETRY_JITTER)));
        }

        return Try.of(() -> client.conversationsList(r -> r
                                .token(accessToken)
                                .limit(1000)
                                .types(List.of(ConversationType.PUBLIC_CHANNEL))
                                .excludeArchived(true)
                                .cursor(cursor))
                        .whenComplete((r, ex) -> {
                            if (ex != null) {
                                logger.warning("Failed to call Slack conversationsList");
                            }
                        })
                        .get())
                .recover(ex -> findConversationListFromApi(client, accessToken, cursor, retryCount + 1, apiDelay))
                .get();
    }

    private Optional<ChannelDetails> getChannelId(ConversationsListResponse response, String channel) {
        return response.getChannels()
                .stream()
                .filter(c -> c.getName().equals(channel))
                .map(c -> new ChannelDetails(channel, c.getId(), c.getContextTeamId()))
                .findFirst();
    }
}
