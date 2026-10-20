package secondbrain.domain.test;

import com.slack.api.methods.AsyncMethodsClient;
import secondbrain.infrastructure.slack.SlackClient;
import secondbrain.infrastructure.slack.api.SlackChannelResource;
import secondbrain.infrastructure.slack.api.SlackConversationResource;
import secondbrain.infrastructure.slack.api.SlackSearchResultResource;

import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An in-memory test instance of SlackClient. Expose it to the class being tested with a @Produces @Preferred method.
 * Channels, histories, usernames and channel names are looked up from maps populated by the test, and every call is
 * recorded as a string. Looking up a value that has not been added throws an IllegalStateException, which simulates a
 * failed Slack API call.
 */
public class FakeSlackClient implements SlackClient {
    public final List<String> calls = new CopyOnWriteArrayList<>();
    private final Map<String, SlackChannelResource> channels = new ConcurrentHashMap<>();
    private final Map<String, String> histories = new ConcurrentHashMap<>();
    private final Map<String, String> usernames = new ConcurrentHashMap<>();
    private final Map<String, String> channelNames = new ConcurrentHashMap<>();
    private final Map<String, Boolean> anyItems = new ConcurrentHashMap<>();
    private final List<SlackSearchResultResource> searchResults = new CopyOnWriteArrayList<>();

    /**
     * Adds a channel that can be found by name, along with its message history.
     */
    public FakeSlackClient addChannel(final String teamId, final String channelId, final String channelName, final String history) {
        channels.put(channelName, new SlackChannelResource(teamId, channelId, channelName));
        histories.put(channelId, history);
        return this;
    }

    /**
     * Adds a channel that can be found by name, but whose message history can not be read.
     */
    public FakeSlackClient addChannelWithoutHistory(final String teamId, final String channelId, final String channelName) {
        channels.put(channelName, new SlackChannelResource(teamId, channelId, channelName));
        return this;
    }

    public FakeSlackClient addUsername(final String userId, final String username) {
        usernames.put(userId, username);
        return this;
    }

    public FakeSlackClient addChannelName(final String channelId, final String channelName) {
        channelNames.put(channelId, channelName);
        return this;
    }

    /**
     * Sets the value returned by the channel version of anyItemsInDuration. Channels without a value fail.
     */
    public FakeSlackClient setAnyItems(final String channelId, final boolean value) {
        anyItems.put(channelId, value);
        return this;
    }

    public FakeSlackClient addSearchResult(final SlackSearchResultResource result) {
        searchResults.add(result);
        return this;
    }

    public List<String> callsStartingWith(final String prefix) {
        return calls.stream().filter(c -> c.startsWith(prefix)).toList();
    }

    @Override
    public boolean anyItemsInDuration(final AsyncMethodsClient client, final String accessToken, final String channelId, final int apiDelay,
                                      final ChronoUnit duration, final ChronoUnit cached) {
        calls.add("anyItems:" + accessToken + ":" + channelId + ":" + apiDelay + ":" + duration + ":" + cached);
        final Boolean value = anyItems.get(channelId);
        if (value == null) {
            throw new IllegalStateException("No anyItems value for " + channelId);
        }
        return value;
    }

    @Override
    public boolean anyItemsInDuration(final AsyncMethodsClient client, final String accessToken, final Set<String> keywords, final int apiDelay,
                                      final ChronoUnit duration, final ChronoUnit cached) {
        calls.add("anyItemsKeywords:" + accessToken + ":" + keywords + ":" + apiDelay + ":" + duration + ":" + cached);
        return !searchResults.isEmpty();
    }

    @Override
    public String conversationHistory(final AsyncMethodsClient client, final String accessToken, final String channelId, final String oldest,
                                      final int ttlSeconds, final int apiDelay, final List<String> ignoredUsers) {
        calls.add("history:" + accessToken + ":" + channelId + ":" + oldest + ":" + ttlSeconds + ":" + apiDelay + ":" + ignoredUsers);
        final String history = histories.get(channelId);
        if (history == null) {
            throw new IllegalStateException("No history for " + channelId);
        }
        return history;
    }

    @Override
    public String username(final AsyncMethodsClient client, final String accessToken, final String userId, final int apiDelay) {
        calls.add("username:" + accessToken + ":" + userId + ":" + apiDelay);
        final String username = usernames.get(userId);
        if (username == null) {
            throw new IllegalStateException("No username for " + userId);
        }
        return username;
    }

    @Override
    public SlackConversationResource channel(final AsyncMethodsClient client, final String accessToken, final String channelId, final int apiDelay) {
        calls.add("channel:" + accessToken + ":" + channelId + ":" + apiDelay);
        final String channelName = channelNames.get(channelId);
        if (channelName == null) {
            throw new IllegalStateException("No channel for " + channelId);
        }
        return new SlackConversationResource(channelName);
    }

    @Override
    public List<SlackSearchResultResource> search(final AsyncMethodsClient client, final String accessToken, final Set<String> keywords,
                                                  final int ttlSeconds, final int apiDelay) {
        calls.add("search:" + accessToken + ":" + keywords + ":" + ttlSeconds + ":" + apiDelay);
        return List.copyOf(searchResults);
    }

    @Override
    public SlackChannelResource findChannelId(final AsyncMethodsClient client, final String accessToken, final String channel, final int apiDelay) {
        calls.add("findChannelId:" + accessToken + ":" + channel + ":" + apiDelay);
        final SlackChannelResource resource = channels.get(channel);
        if (resource == null) {
            throw new IllegalStateException("No channel named " + channel);
        }
        return resource;
    }
}
