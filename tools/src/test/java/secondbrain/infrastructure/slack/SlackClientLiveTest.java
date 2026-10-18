package secondbrain.infrastructure.slack;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.mutex.Mutex;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.timeout.TimeoutService;
import com.slack.api.RequestConfigurator;
import com.slack.api.methods.AsyncMethodsClient;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.request.conversations.ConversationsHistoryRequest;
import com.slack.api.methods.request.conversations.ConversationsListRequest;
import com.slack.api.methods.request.conversations.ConversationsRepliesRequest;
import com.slack.api.methods.request.search.SearchAllRequest;
import com.slack.api.methods.request.users.UsersInfoRequest;
import com.slack.api.methods.response.conversations.ConversationsHistoryResponse;
import com.slack.api.methods.response.conversations.ConversationsInfoResponse;
import com.slack.api.methods.response.conversations.ConversationsListResponse;
import com.slack.api.methods.response.conversations.ConversationsRepliesResponse;
import com.slack.api.methods.response.search.SearchAllResponse;
import com.slack.api.methods.response.users.UsersInfoResponse;
import com.slack.api.model.*;
import okhttp3.Protocol;
import okhttp3.Request;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import secondbrain.domain.exceptions.ExternalFailure;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.test.DirectTimeoutService;
import secondbrain.domain.test.RecordingLocalStorage;
import secondbrain.domain.test.RecordingMutex;
import secondbrain.infrastructure.slack.api.SlackChannelResource;
import secondbrain.infrastructure.slack.api.SlackSearchResultResource;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Note: the client rate limits itself to one Slack API call per second, so each test keeps the number of calls small.
 */
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(SlackClientLive.class)
@AddBeanClasses(Loggers.class)
class SlackClientLiveTest {
    private final RecordingLocalStorage localStorage = new RecordingLocalStorage();
    private final RecordingMutex mutex = new RecordingMutex();
    private final AsyncMethodsClient slack = mock(AsyncMethodsClient.class);

    @Inject
    @Preferred
    private SlackClientLive client;

    @Produces
    @Preferred
    LocalStorage produceLocalStorage() {
        return localStorage;
    }

    @Produces
    @Preferred
    Mutex produceMutex() {
        return mutex;
    }

    @Produces
    TimeoutService produceTimeoutService() {
        return new DirectTimeoutService();
    }

    private static Message message(final String user, final String text, final String ts, final int replies) {
        final Message message = new Message();
        message.setUser(user);
        message.setText(text);
        message.setTs(ts);
        message.setReplyCount(replies);
        return message;
    }

    private static ConversationsHistoryResponse history(final Message... messages) {
        final ConversationsHistoryResponse response = new ConversationsHistoryResponse();
        response.setMessages(new ArrayList<>(List.of(messages)));
        return response;
    }

    private static SlackApiException slackError(final int code) {
        final okhttp3.Response response = new okhttp3.Response.Builder()
                .request(new Request.Builder().url("https://slack.com/api/test").build())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("status " + code)
                .build();
        return new SlackApiException(response, "");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <B> B configured(final ArgumentCaptor<RequestConfigurator> captor, final int index, final B builder) {
        return (B) captor.getAllValues().get(index).configure(builder);
    }

    @Test
    void conversationHistoryRejectsBlankArguments() {
        assertThrows(IllegalArgumentException.class, () -> client.conversationHistory(slack, "", "C1", "100", 60, 0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> client.conversationHistory(slack, "token", " ", "100", 60, 0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> client.conversationHistory(slack, "token", "C1", "", 60, 0, List.of()));
        verifyNoInteractions(slack);
    }

    @Test
    void conversationHistoryJoinsMessagesWithThreadRepliesAndDropsIgnoredUsers() {
        when(slack.conversationsHistory(any(RequestConfigurator.class))).thenReturn(CompletableFuture.completedFuture(history(
                message("U1", "hello", "1.0", 0),
                message("U2", "parent", "2.0", 3),
                message("BOT_RELEASE", "noise", "3.0", 0))));
        final ConversationsRepliesResponse replies = new ConversationsRepliesResponse();
        replies.setMessages(List.of(message("U2", "parent", "2.0", 3), message("U3", "reply one", "2.1", 0),
                message("U4", " ", "2.2", 0), message("U5", "reply two", "2.3", 0)));
        when(slack.conversationsReplies(any(RequestConfigurator.class))).thenReturn(CompletableFuture.completedFuture(replies));

        final String text = client.conversationHistory(slack, "token", "C1", "100", 60, 0, List.of("bot"));

        assertEquals("\nhello\nparent\n  > reply one\n  > reply two", text);

        final ArgumentCaptor<RequestConfigurator> historyRequest = ArgumentCaptor.forClass(RequestConfigurator.class);
        verify(slack).conversationsHistory(historyRequest.capture());
        final ConversationsHistoryRequest sent = configured(historyRequest, 0, ConversationsHistoryRequest.builder()).build();
        assertEquals("token", sent.getToken());
        assertEquals("C1", sent.getChannel());
        assertEquals("100", sent.getOldest());

        final ArgumentCaptor<RequestConfigurator> repliesRequest = ArgumentCaptor.forClass(RequestConfigurator.class);
        verify(slack).conversationsReplies(repliesRequest.capture());
        assertEquals("2.0", configured(repliesRequest, 0, ConversationsRepliesRequest.builder()).build().getTs());

        assertEquals(List.of(new RecordingLocalStorage.Call("SlackClientLive", "SlackAPIConversationHistory", DigestUtils.sha256Hex("C1" + "100"), 60)),
                localStorage.calls);
    }

    @Test
    void conversationHistoryWrapsApiFailures() {
        when(slack.conversationsHistory(any(RequestConfigurator.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("socket closed")));

        assertThrows(ExternalFailure.class, () -> client.conversationHistory(slack, "token", "C1", "100", 60, 0, List.of()));
    }

    @Test
    void usernameReturnsUserNameAndLocksOnUsers() {
        final User user = new User();
        user.setName("jane");
        final UsersInfoResponse response = new UsersInfoResponse();
        response.setUser(user);
        when(slack.usersInfo(any(RequestConfigurator.class))).thenReturn(CompletableFuture.completedFuture(response));

        assertEquals("jane", client.username(slack, "token", "U1", 0));

        final ArgumentCaptor<RequestConfigurator> request = ArgumentCaptor.forClass(RequestConfigurator.class);
        verify(slack).usersInfo(request.capture());
        assertEquals("U1", configured(request, 0, UsersInfoRequest.builder()).build().getUser());
        assertEquals(List.of("sb_slack.lock.users"), mutex.lockNames);
        assertEquals(new RecordingLocalStorage.Call("SlackClientLive", "SlackAPIUserInfo", DigestUtils.sha256Hex("U1"), RecordingLocalStorage.NO_TTL),
                localStorage.calls.getFirst());
    }

    @Test
    void usernameFallsBackToUnknownUserWhenLookupFails() {
        doAnswer(inv -> {
            throw slackError(404);
        }).when(slack).usersInfo(any(RequestConfigurator.class));

        assertEquals("Unknown user", client.username(slack, "token", "U404", 0));
    }

    @Test
    void usernameRetriesRateLimitedLookups() {
        // The retry sleeps for apiDelay plus up to 10 seconds of jitter. A pending interrupt aborts that sleep
        // (vavr rethrows InterruptedException as fatal), which proves a retry was started without waiting for it.
        doAnswer(inv -> {
            Thread.currentThread().interrupt();
            throw slackError(429);
        }).when(slack).usersInfo(any(RequestConfigurator.class));

        final Throwable thrown = assertThrows(Throwable.class, () -> client.username(slack, "token", "U429", 0));

        assertInstanceOf(InterruptedException.class, thrown);
        assertEquals(List.of("sb_slack.lock.users", "sb_slack.lock.users"), mutex.lockNames);
        assertFalse(Thread.interrupted());
    }

    @Test
    void channelReturnsChannelName() {
        final Conversation conversation = new Conversation();
        conversation.setName("general");
        final ConversationsInfoResponse response = new ConversationsInfoResponse();
        response.setChannel(conversation);
        when(slack.conversationsInfo(any(RequestConfigurator.class))).thenReturn(CompletableFuture.completedFuture(response));

        assertEquals("general", client.channel(slack, "token", "C1", 0).channelName());
        assertEquals(List.of("sb_slack.lock.channel"), mutex.lockNames);
        assertEquals("SlackAPIConversationsInfo", localStorage.calls.getFirst().source());
    }

    @Test
    void channelFallsBackToUnknownChannelWhenLookupFails() {
        when(slack.conversationsInfo(any(RequestConfigurator.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("down")));

        assertEquals("Unknown channel", client.channel(slack, "token", "C404", 0).channelName());
    }

    @Test
    void searchMapsMatchesAndJoinsKeywordsIntoQuery() {
        final Channel channel = new Channel();
        channel.setName("support");
        final MatchedItem match = new MatchedItem();
        match.setId("M1");
        match.setTs("123.4");
        match.setChannel(channel);
        match.setText("customer issue");
        match.setPermalink("https://slack/p1");
        final SearchResult messages = new SearchResult();
        messages.setMatches(List.of(match));
        final SearchAllResponse response = new SearchAllResponse();
        response.setMessages(messages);
        when(slack.searchAll(any(RequestConfigurator.class))).thenReturn(CompletableFuture.completedFuture(response));

        final List<SlackSearchResultResource> results = client.search(slack, "token", Set.of("acme"), 30, 0);

        assertEquals(List.of(new SlackSearchResultResource("M1", "123.4", "support", "customer issue", "https://slack/p1")), results);
        final ArgumentCaptor<RequestConfigurator> request = ArgumentCaptor.forClass(RequestConfigurator.class);
        verify(slack).searchAll(request.capture());
        assertEquals("acme", configured(request, 0, SearchAllRequest.builder()).build().getQuery());
        assertEquals(new RecordingLocalStorage.Call("SlackClientLive", "SlackAPISearch", DigestUtils.sha256Hex("acme"), 30),
                localStorage.calls.getFirst());
    }

    @Test
    void searchWithoutMessagesReturnsEmptyList() {
        when(slack.searchAll(any(RequestConfigurator.class))).thenReturn(CompletableFuture.completedFuture(new SearchAllResponse()));

        assertEquals(List.of(), client.search(slack, "token", Set.of("nothing"), 30, 0));
    }

    @Test
    void searchRetriesAnyFailure() {
        when(slack.searchAll(any(RequestConfigurator.class))).thenAnswer(inv -> {
            Thread.currentThread().interrupt();
            return CompletableFuture.failedFuture(new IllegalStateException("flaky"));
        });

        final Throwable thrown = assertThrows(Throwable.class, () -> client.search(slack, "token", Set.of("acme"), 30, 0));

        // The retry started (and its backoff sleep was aborted by the pending interrupt)
        assertInstanceOf(InterruptedException.class, thrown);
        verify(slack, times(1)).searchAll(any(RequestConfigurator.class));
        assertFalse(Thread.interrupted());
    }

    @Test
    void findChannelIdPagesThroughPublicChannelsUntilCursorIsEmpty() {
        final ConversationsListResponse first = new ConversationsListResponse();
        first.setChannels(List.of(conversation("general", "C1", "T1")));
        final ResponseMetadata more = new ResponseMetadata();
        more.setNextCursor("cursor-2");
        first.setResponseMetadata(more);
        final ConversationsListResponse second = new ConversationsListResponse();
        second.setChannels(List.of(conversation("acme-support", "C2", "T2")));
        when(slack.conversationsList(any(RequestConfigurator.class)))
                .thenReturn(CompletableFuture.completedFuture(first), CompletableFuture.completedFuture(second));

        final SlackChannelResource result = client.findChannelId(slack, "token", "acme-support", 0);

        assertEquals(new SlackChannelResource("T2", "C2", "acme-support"), result);
        final ArgumentCaptor<RequestConfigurator> request = ArgumentCaptor.forClass(RequestConfigurator.class);
        verify(slack, times(2)).conversationsList(request.capture());
        final ConversationsListRequest firstRequest = configured(request, 0, ConversationsListRequest.builder()).build();
        final ConversationsListRequest secondRequest = configured(request, 1, ConversationsListRequest.builder()).build();
        assertNull(firstRequest.getCursor());
        assertEquals("cursor-2", secondRequest.getCursor());
        assertEquals(1000, firstRequest.getLimit());
        assertTrue(firstRequest.isExcludeArchived());
        assertEquals(List.of(ConversationType.PUBLIC_CHANNEL), firstRequest.getTypes());
        assertEquals(List.of("SlackAPIChannel", "SlackAPIChannelListV2"), localStorage.calls.stream().map(RecordingLocalStorage.Call::source).toList());
    }

    @Test
    void findChannelIdFailsWhenChannelDoesNotExist() {
        final ConversationsListResponse only = new ConversationsListResponse();
        only.setChannels(List.of(conversation("general", "C1", "T1")));
        when(slack.conversationsList(any(RequestConfigurator.class))).thenReturn(CompletableFuture.completedFuture(only));

        final InternalFailure ex = assertThrows(InternalFailure.class, () -> client.findChannelId(slack, "token", "missing", 0));

        assertTrue(String.valueOf(ex.getMessage()).endsWith("Failed to find channel missing"));
    }

    @Test
    void anyItemsInDurationForChannelReflectsWhetherMessagesExist() {
        when(slack.conversationsHistory(any(RequestConfigurator.class)))
                .thenReturn(CompletableFuture.completedFuture(history(message("U1", "recent", "1.0", 0))),
                        CompletableFuture.completedFuture(history()));

        assertTrue(client.anyItemsInDuration(slack, "token", "C1", 0, ChronoUnit.MONTHS, ChronoUnit.WEEKS));
        assertFalse(client.anyItemsInDuration(slack, "token", "C2", 0, ChronoUnit.MONTHS, ChronoUnit.WEEKS));

        assertEquals("SlackAPIConversationHistoryDuration", localStorage.calls.getFirst().source());
        assertEquals(ChronoUnit.WEEKS.getDuration().toSeconds(), localStorage.calls.getFirst().ttlSeconds());
    }

    @Test
    void anyItemsInDurationForKeywordsAddsAfterDateToSearch() {
        when(slack.searchAll(any(RequestConfigurator.class))).thenReturn(CompletableFuture.completedFuture(new SearchAllResponse()));

        assertFalse(client.anyItemsInDuration(slack, "token", Set.of("acme"), 0, ChronoUnit.MONTHS, ChronoUnit.MONTHS));

        final ArgumentCaptor<RequestConfigurator> request = ArgumentCaptor.forClass(RequestConfigurator.class);
        verify(slack).searchAll(request.capture());
        final String query = configured(request, 0, SearchAllRequest.builder()).build().getQuery();
        assertTrue(query.contains("acme"), query);
        assertTrue(query.contains("after:"), query);
    }

    private static Conversation conversation(final String name, final String id, final String teamId) {
        final Conversation conversation = new Conversation();
        conversation.setName(name);
        conversation.setId(id);
        conversation.setContextTeamId(teamId);
        return conversation;
    }
}
