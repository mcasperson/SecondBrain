package secondbrain.infrastructure.slack;

import com.slack.api.methods.AsyncMethodsClient;
import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.test.RecordingLlmClient;
import secondbrain.infrastructure.llm.LlmClient;
import secondbrain.infrastructure.slack.api.SlackChannelResource;
import secondbrain.infrastructure.slack.api.SlackConversationResource;
import secondbrain.infrastructure.slack.api.SlackSearchResultResource;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(SlackClientMock.class)
class SlackClientMockTest {
    private static final String TOKEN = "token";

    private final AsyncMethodsClient slack = mock(AsyncMethodsClient.class);
    private final AtomicInteger llmCalls = new AtomicInteger();
    private final RecordingLlmClient llmClient = new RecordingLlmClient()
            .respondWith(document -> "llm-" + llmCalls.incrementAndGet());

    @Inject
    private SlackClientMock client;

    @Produces
    @Preferred
    LlmClient produceLlmClient() {
        return llmClient;
    }

    private String prompt(final int index) {
        return llmClient.requests.get(index).document().getPrompts().get(0);
    }

    @Test
    void anyItemsInDurationIsAlwaysTrueForChannelsAndKeywords() {
        assertTrue(client.anyItemsInDuration(slack, TOKEN, "C123", 0, ChronoUnit.DAYS, ChronoUnit.HOURS));
        assertTrue(client.anyItemsInDuration(slack, TOKEN, Set.of("keyword"), 0, ChronoUnit.DAYS, ChronoUnit.HOURS));
        assertTrue(llmClient.requests.isEmpty());
    }

    @Test
    void conversationHistoryReturnsLlmConversation() {
        assertEquals("llm-1", client.conversationHistory(slack, TOKEN, "C123", "0", 60, 0, List.of()));
        assertTrue(prompt(0).contains("Slack conversation history"), prompt(0));
    }

    @Test
    void usernameReturnsLlmUsername() {
        assertEquals("llm-1", client.username(slack, TOKEN, "U1", 0));
        assertTrue(prompt(0).contains("Slack username"), prompt(0));
    }

    @Test
    void channelReturnsLlmChannelName() {
        assertEquals(new SlackConversationResource("llm-1"), client.channel(slack, TOKEN, "C123", 0));
        assertTrue(prompt(0).contains("Slack channel name"), prompt(0));
    }

    @Test
    void searchReturnsSingleResultBuiltFromFourLlmCalls() {
        final ZonedDateTime before = ZonedDateTime.now(ZoneId.of("UTC")).truncatedTo(ChronoUnit.SECONDS);

        final List<SlackSearchResultResource> results = client.search(slack, TOKEN, Set.of("keyword"), 60, 0);

        assertEquals(1, results.size());
        final SlackSearchResultResource result = results.get(0);
        assertEquals("llm-1", result.id());
        assertEquals("llm-2", result.channelName());
        assertEquals("llm-3", result.text());
        assertEquals("llm-4", result.permalink());
        assertTrue(prompt(0).contains("message ID"), prompt(0));
        assertTrue(prompt(1).contains("channel name"), prompt(1));
        assertTrue(prompt(2).contains("single message"), prompt(2));
        assertTrue(prompt(3).contains("URL"), prompt(3));

        final ZonedDateTime timestamp = ZonedDateTime.parse(
                Objects.requireNonNull(result.timestamp()),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"));
        assertEquals("UTC", timestamp.getZone().getId());
        final Duration age = Duration.between(before, timestamp);
        assertFalse(age.isNegative(), result.timestamp());
        assertTrue(age.compareTo(Duration.ofMinutes(1)) < 0, result.timestamp());
    }

    @Test
    void findChannelIdUsesLlmChannelIdAndTeamIdWithRequestedChannelName() {
        final SlackChannelResource channel = client.findChannelId(slack, TOKEN, "general", 0);

        assertEquals("llm-1", channel.channelId());
        assertEquals("llm-2", channel.teamId());
        assertEquals("general", channel.channelName());
        assertTrue(prompt(0).contains("channel ID"), prompt(0));
        assertTrue(prompt(1).contains("team ID"), prompt(1));
    }
}
