package secondbrain.infrastructure.slack;

import com.google.common.util.concurrent.RateLimiter;
import com.slack.api.methods.AsyncMethodsClient;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.response.conversations.ConversationsHistoryResponse;
import com.slack.api.methods.response.conversations.ConversationsInfoResponse;
import com.slack.api.methods.response.conversations.ConversationsListResponse;
import com.slack.api.methods.response.conversations.ConversationsRepliesResponse;
import com.slack.api.methods.response.search.SearchAllResponse;
import com.slack.api.methods.response.users.UsersInfoResponse;
import com.slack.api.model.*;
import io.vavr.API;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.date.DateTruncate;
import secondbrain.domain.exceptions.ExternalFailure;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.mutex.Mutex;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.timeout.TimeoutService;
import secondbrain.domain.tools.slack.ChannelDetails;
import secondbrain.infrastructure.slack.api.SlackChannelResource;
import secondbrain.infrastructure.slack.api.SlackChannelWithReplies;
import secondbrain.infrastructure.slack.api.SlackConversationResource;
import secondbrain.infrastructure.slack.api.SlackSearchResultResource;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkArgument;
import static io.vavr.Predicates.instanceOf;
import static java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME;

@ApplicationScoped
public class SlackClientLive implements SlackClient {

    private static final int RETRIES = 10;
    private static final int RETRY_JITTER = 10000;
    private static final RateLimiter RATE_LIMITER = RateLimiter.create(1);
    private static final int API_TIMEOUT_SECONDS = 60;
    private static final int CHANNEL_TTL_SECONDS = 60 * 60 * 24 * 365;
    private static final int CHANNEL_LIST_TTL_SECONDS = 60 * 60 * 24 * 7;

    @Inject
    @ConfigProperty(name = "sb.slack.lock", defaultValue = "sb_slack.lock")
    private String lockFile;

    @Inject
    @Preferred
    private LocalStorage localStorage;

    @Inject
    private Logger logger;

    @Inject
    @Preferred
    private Mutex mutex;

    @Inject
    private TimeoutService timeoutService;

    @Override
    public boolean anyItemsInDuration(
            final AsyncMethodsClient client,
            final String accessToken,
            final String channelId,
            final int apiDelay,
            final ChronoUnit duration,
            final ChronoUnit cached) {
        final String oldest = String.valueOf(DateTruncate.truncate(OffsetDateTime.now(ZoneId.systemDefault())
                .minus(1, duration), duration).toEpochSecond());

        return org.apache.commons.lang3.StringUtils.isNotBlank(
                conversationHistory(client, accessToken, channelId, oldest,
                        (int) cached.getDuration().toSeconds(), apiDelay,
                        "SlackAPIConversationHistoryDuration"));
    }

    @Override
    public boolean anyItemsInDuration(
            final AsyncMethodsClient client,
            final String accessToken,
            final Set<String> keywords,
            final int apiDelay,
            final ChronoUnit duration,
            final ChronoUnit cached) {
        final String afterDate = DateTruncate.truncate(OffsetDateTime.now(ZoneId.systemDefault())
                .minus(1, duration), duration).format(ISO_OFFSET_DATE_TIME);
        final Set<String> durationKeywords = new HashSet<>(keywords);
        durationKeywords.add("after:" + afterDate);
        return !search(client, accessToken, durationKeywords,
                (int) duration.getDuration().toSeconds(), apiDelay).isEmpty();
    }

    @Override
    public String conversationHistory(
            final AsyncMethodsClient client,
            final String accessToken,
            final String channelId,
            final String oldest,
            final int ttlSeconds,
            final int apiDelay) {
        return conversationHistory(client, accessToken, channelId, oldest, ttlSeconds, apiDelay,
                "SlackAPIConversationHistory");
    }

    private String conversationHistory(
            final AsyncMethodsClient client,
            final String accessToken,
            final String channelId,
            final String oldest,
            final int ttlSeconds,
            final int apiDelay,
            final String source) {
        checkArgument(StringUtils.isNotBlank(accessToken));
        checkArgument(StringUtils.isNotBlank(channelId));
        checkArgument(StringUtils.isNotBlank(oldest));

        /*
            The Slack API enforces a lot of API rate limits. So we will cache the results of a channel lookup
            based on a hash of the channel name and the access token.
         */
        final String hash = DigestUtils.sha256Hex(channelId + oldest);

        return Try
                .of(() -> localStorage.getOrPutObject(
                                SlackClientLive.class.getSimpleName(),
                                source,
                                hash,
                                ttlSeconds,
                                SlackChannelWithReplies.class,
                                () -> conversationHistoryFromApi(client, accessToken, channelId, oldest, 0, apiDelay))
                        .result())
                .map(this::conversationsToText)
                .get();
    }

    private String conversationsToText(final @Nullable SlackChannelWithReplies conversation) {
        if (conversation == null || conversation.history() == null) {
            return "";
        }

        final Map<String, ConversationsRepliesResponse> repliesByTs =
                Objects.requireNonNullElse(conversation.repliesByThreadTs(), Map.<String, ConversationsRepliesResponse>of());

        return Objects.requireNonNullElse(conversation.history().getMessages(), List.<Message>of())
                .stream()
                .map(message -> {
                    final String messageText = Objects.requireNonNullElse(message.getText(), "");
                    final String ts = message.getTs();
                    if (ts == null || message.getReplyCount() == null || message.getReplyCount() == 0) {
                        return messageText;
                    }

                    final ConversationsRepliesResponse thread = repliesByTs.get(ts);
                    if (thread == null) {
                        return messageText;
                    }

                    final String replyText = Objects.requireNonNullElse(thread.getMessages(), List.<Message>of())
                            .stream()
                            .skip(1) // first message is the root, already included above
                            .map(m -> Objects.requireNonNullElse(m.getText(), ""))
                            .filter(StringUtils::isNotBlank)
                            .reduce("", (a, b) -> a + "\n  > " + b);

                    return replyText.isEmpty() ? messageText : messageText + replyText;
                })
                .reduce("", (a, b) -> a + "\n" + b);
    }

    private SlackChannelWithReplies conversationHistoryFromApi(
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
            logger.fine("Retrying Slack conversationsHistory");
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
                        return conversationHistoryFromApi(client, accessToken, channelId, oldest, retryCount + 1, apiDelay).history();
                    }

                    throw new ExternalFailure("Could not call searchAll", ex);
                });

        final ConversationsHistoryResponse history = result
                .mapFailure(API.Case(API.$(), ex -> new ExternalFailure("Could not call conversationsHistory", ex)))
                .get();

        final Map<String, ConversationsRepliesResponse> repliesByThreadTs =
                Objects.requireNonNullElse(history.getMessages(), List.<Message>of())
                        .stream()
                        .filter(m -> m.getTs() != null && m.getReplyCount() != null && m.getReplyCount() > 0)
                        .collect(Collectors.toMap(
                                Message::getTs,
                                m -> conversationRepliesFromApi(client, accessToken, channelId, m.getTs(), 0, apiDelay)));

        return new SlackChannelWithReplies(history, repliesByThreadTs);
    }

    private ConversationsRepliesResponse conversationRepliesFromApi(
            final AsyncMethodsClient client,
            final String accessToken,
            final String channelId,
            final String threadTs,
            final int retryCount,
            final int apiDelay) {
        if (retryCount > RETRIES) {
            throw new InternalFailure("Could not call conversationsReplies after " + RETRIES + " retries");
        }

        RATE_LIMITER.acquire();

        if (retryCount > 0) {
            logger.fine("Retrying Slack conversationsReplies");
            Try.run(() -> Thread.sleep(apiDelay + (int) (Math.random() * RETRY_JITTER)));
        }

        final Try<ConversationsRepliesResponse> result = Try.of(() -> client.conversationsReplies(r -> r
                                .token(accessToken)
                                .channel(channelId)
                                .ts(threadTs))
                        .whenComplete((r, ex) -> {
                            if (ex != null) {
                                logger.warning("Failed to call Slack conversationsReplies");
                            }
                        })
                        .get())
                .recover(SlackApiException.class, ex -> {
                    if (ex.getResponse().code() == 429) {
                        return conversationRepliesFromApi(client, accessToken, channelId, threadTs, retryCount + 1, apiDelay);
                    }

                    throw new ExternalFailure("Could not call conversationsReplies", ex);
                });

        return result
                .mapFailure(API.Case(API.$(), ex -> new ExternalFailure("Could not call conversationsReplies", ex)))
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
            logger.fine("Retrying Slack usersInfo");
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

    @Override
    public SlackConversationResource channel(
            final AsyncMethodsClient client,
            final String accessToken,
            final String channelId,
            final int apiDelay) {
        return timeoutService.executeWithTimeout(() -> channelTimrout(
                        client,
                        accessToken,
                        channelId,
                        apiDelay),
                () -> {
                    throw new ExternalFailure("Slack channel timed out");
                },
                API_TIMEOUT_SECONDS);
    }

    private SlackConversationResource channelTimrout(
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
            logger.fine("Retrying Slack channel");
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
        return timeoutService.executeWithTimeout(() -> searchTimeout(
                        client,
                        accessToken,
                        keywords,
                        ttlSeconds,
                        apiDelay),
                () -> {
                    throw new ExternalFailure("Slack search timed out");
                },
                API_TIMEOUT_SECONDS);
    }

    private List<SlackSearchResultResource> searchTimeout(
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
                .map(s -> (s == null || s.getMessages() == null ? List.<MatchedItem>of() : s.getMessages().getMatches())
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
            logger.fine("Retrying Slack searchAll");
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
                        CHANNEL_TTL_SECONDS,
                        ChannelDetails.class,
                        () -> findChannelIdFromApi(client, accessToken, channel, apiDelay)).result())
                .filter(Objects::nonNull)
                .map(c -> new SlackChannelResource(c.teamId(), c.channelId(), c.channelName()))
                .get();
    }

    private ChannelDetails findChannelIdFromApi(
            final AsyncMethodsClient client,
            final String accessToken,
            final String channel,
            final int apiDelay) {
        return mutex.acquire(
                lockFile + ".channelid",
                () -> findChannelIdFromApiLocked(client, accessToken, channel, apiDelay));
    }

    private ChannelDetails findChannelIdFromApiLocked(
            final AsyncMethodsClient client,
            final String accessToken,
            final String channel,
            final int apiDelay) {

        final List<ChannelDetails> conversations = Try
                .of(() -> localStorage.getOrPutList(
                        SlackClientLive.class.getSimpleName(),
                        "SlackAPIChannelListV2",
                        DigestUtils.sha256Hex(accessToken),
                        CHANNEL_LIST_TTL_SECONDS,
                        ChannelDetails.class,
                        () -> findConversationListFromApiUntilCursorEmptyLocked(
                                client,
                                accessToken,
                                apiDelay)).result())
                .get();

        return conversations
                .stream()
                .filter(c -> channel.equals(c.channelName()))
                .findFirst()
                .map(c -> new ChannelDetails(channel, c.channelId(), c.teamId()))
                .orElseThrow(() -> new InternalFailure(
                        "Slack channel API reached the end of the results. Failed to find channel " + channel));
    }

    private List<ChannelDetails> findConversationListFromApiUntilCursorEmptyLocked(
            final AsyncMethodsClient client,
            final String accessToken,
            final int apiDelay) {
        final List<ChannelDetails> conversations = new ArrayList<>();
        @Nullable String cursor = null;

        while (true) {
            final ConversationsListResponse response = findConversationListFromApiLocked(client, accessToken, cursor, 0, apiDelay);
            conversations.addAll(Objects.requireNonNullElse(response.getChannels(), List.<Conversation>of())
                    .stream()
                    .map(c -> new ChannelDetails(c.getName(), c.getId(), c.getContextTeamId()))
                    .toList());

            cursor = Optional.ofNullable(response.getResponseMetadata())
                    .map(ResponseMetadata::getNextCursor)
                    .orElse(null);

            if (StringUtils.isBlank(cursor)) {
                return conversations;
            }
        }
    }

    private ConversationsListResponse findConversationListFromApiLocked(
            final AsyncMethodsClient client,
            final String accessToken,
            @Nullable final String cursor,
            final int retryCount,
            final int apiDelay) {

        if (retryCount > RETRIES) {
            throw new InternalFailure("Could not call conversationsList after " + RETRIES + " retries");
        }

        RATE_LIMITER.acquire();

        if (retryCount > 0) {
            logger.fine("Retrying Slack conversationsList");
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
                .recover(ex -> findConversationListFromApiLocked(client, accessToken, cursor, retryCount + 1, apiDelay))
                .get();
    }
}
