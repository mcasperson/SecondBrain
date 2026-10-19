package secondbrain.infrastructure.youtube;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.ParameterizedTest;
import secondbrain.domain.exceptions.InvalidResponse;
import secondbrain.domain.httpclient.HttpClientCaller;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.mutex.Mutex;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.response.OkResponseValidation;
import secondbrain.domain.test.DirectHttpClientCaller;
import secondbrain.domain.test.FakeJaxRs;
import secondbrain.domain.test.FakeYoutubeTranscripts;
import secondbrain.domain.test.FixedClientConstructor;
import secondbrain.domain.test.RecordingLocalStorage;
import secondbrain.domain.test.RecordingMutex;
import secondbrain.domain.test.TestConfigUtil;
import secondbrain.domain.web.ClientConstructor;
import secondbrain.infrastructure.youtube.api.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(YoutubeClientLive.class)
@AddBeanClasses(OkResponseValidation.class)
@AddBeanClasses(Loggers.class)
// The YouTube API records are built with null fields to match real responses
@SuppressWarnings("NullAway")
class YoutubeClientLiveTest {
    private static final String KEY = "yt-key";
    private static final String TOOL = "YoutubeClientLive";
    private static final String PLAYLIST_URL = "https://www.googleapis.com/youtube/v3/playlistItems?part=snippet&maxResults=50&playlistId=PL1&key=" + KEY;
    private static final String SEARCH_URL = "https://www.googleapis.com/youtube/v3/search?part=snippet&maxResults=50&channelId=CH1&q=octopus&type=video&key=" + KEY;

    private final DirectHttpClientCaller httpCaller = new DirectHttpClientCaller();
    private final RecordingLocalStorage localStorage = new RecordingLocalStorage();
    private final RecordingMutex mutex = new RecordingMutex();
    private final FakeJaxRs http = new FakeJaxRs();
    private final FakeYoutubeTranscripts transcripts = new FakeYoutubeTranscripts();

    @Inject
    private YoutubeClientLive client;

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
    HttpClientCaller produceHttpClientCaller() {
        return httpCaller;
    }

    @Produces
    ClientConstructor produceClientConstructor() {
        return new FixedClientConstructor(http.client);
    }

    @Produces
    YoutubeTranscripts produceYoutubeTranscripts() {
        return transcripts;
    }

    @BeforeEach
    void registerConfig() {
        // The default rate limit allows one request every 33 seconds
        TestConfigUtil.registerConfig(Map.of("sb.youtube.rateLimitPerSecond", "1000"));
    }

    private static YoutubePlaylistsItem playlistItem(final String videoId) {
        return new YoutubePlaylistsItem("item-" + videoId, new YoutubePlaylistsItemSnippet("Title " + videoId, new YoutubePlaylistsItemSnippetResourceId("youtube#video", videoId)));
    }

    private static YoutubeSearchItem searchItem(final String videoId) {
        return new YoutubeSearchItem(new YoutubeSearchItemId(videoId), new YoutubeSearchItemSnippet("Title " + videoId));
    }

    private static YoutubePlaylists playlistPage(final String nextPageToken, final YoutubePlaylistsItem... items) {
        return new YoutubePlaylists(Arrays.asList(items), nextPageToken);
    }

    private static YoutubeSearch searchPage(final String nextPageToken, final YoutubeSearchItem... items) {
        return new YoutubeSearch(nextPageToken, Arrays.asList(items));
    }

    @Test
    void getPlaylistItemsFollowsThePageTokens() {
        http.enqueue(FakeJaxRs.response(200, playlistPage("page-2", playlistItem("v1"), playlistItem("v2"))))
                .enqueue(FakeJaxRs.response(200, playlistPage(null, playlistItem("v3"))));

        final List<YoutubePlaylistsItem> result = client.getPlaylistItems("PL1", "", KEY);

        assertEquals(List.of(playlistItem("v1"), playlistItem("v2"), playlistItem("v3")), result);
        assertEquals(List.of(PLAYLIST_URL, PLAYLIST_URL + "&pageToken=page-2"), http.targets());
        assertEquals(List.of("application/json", "application/json"), http.headers("Accept"));
        // Without a starting page token the cache key is just the playlist ID
        assertEquals(List.of(new RecordingLocalStorage.Call(TOOL, "YoutubeAPIPlaylistsV2", "PL1", RecordingLocalStorage.NO_TTL)), localStorage.calls);
    }

    @Test
    void getPlaylistItemsStartsFromTheSuppliedPageToken() {
        http.enqueue(FakeJaxRs.response(200, playlistPage(" ", playlistItem("v9"))));

        assertEquals(List.of(playlistItem("v9")), client.getPlaylistItems("PL1", "page-9", KEY));
        assertEquals(List.of(PLAYLIST_URL + "&pageToken=page-9"), http.targets());
        assertEquals(List.of(new RecordingLocalStorage.Call(TOOL, "YoutubeAPIPlaylistsV2", "PL1-page-9", RecordingLocalStorage.NO_TTL)), localStorage.calls);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void getPlaylistItemsUsesThePlaylistIdAsTheKeyForABlankPageToken(final String pageToken) {
        http.enqueue(FakeJaxRs.response(200, playlistPage(null, playlistItem("v1"))));

        client.getPlaylistItems("PL1", pageToken, KEY);

        assertEquals(List.of(new RecordingLocalStorage.Call(TOOL, "YoutubeAPIPlaylistsV2", "PL1", RecordingLocalStorage.NO_TTL)), localStorage.calls);
    }

    @Test
    void getPlaylistItemsCachesEachStartingPageTokenSeparately() {
        http.enqueue(FakeJaxRs.response(200, playlistPage(null, playlistItem("v1"))))
                .enqueue(FakeJaxRs.response(200, playlistPage(null, playlistItem("v9"))));

        client.getPlaylistItems("PL1", "", KEY);
        client.getPlaylistItems("PL1", "page-9", KEY);

        assertEquals(List.of("PL1", "PL1-page-9"), localStorage.calls.stream().map(RecordingLocalStorage.Call::promptHash).toList());
    }

    @Test
    void getPlaylistItemsTreatsMissingItemsAsEmpty() {
        http.enqueue(FakeJaxRs.response(200, new YoutubePlaylists(null, null)));

        assertEquals(List.of(), client.getPlaylistItems("PL1", "", KEY));
    }

    @Test
    void getPlaylistItemsUsesTheCache() {
        localStorage.prime("YoutubeAPIPlaylistsV2", new YoutubePlaylistsItem[]{playlistItem("cached")});

        assertEquals(List.of(playlistItem("cached")), client.getPlaylistItems("PL1", "", KEY));
        assertEquals(0, httpCaller.calls());
    }

    @Test
    void getPlaylistItemsWrapsApiFailures() {
        http.enqueue(FakeJaxRs.response(403, "quota"));

        final RuntimeException ex = assertThrows(RuntimeException.class, () -> client.getPlaylistItems("PL1", "", KEY));

        assertEquals("Failed to get Youtube playlist", ex.getMessage());
        assertInstanceOf(InvalidResponse.class, ex.getCause());
    }

    @Test
    void searchVideosFollowsThePageTokens() {
        http.enqueue(FakeJaxRs.response(200, searchPage("page-2", searchItem("v1"))))
                .enqueue(FakeJaxRs.response(200, searchPage("", searchItem("v2"), searchItem("v3"))));

        final List<YoutubeSearchItem> result = client.searchVideos("octopus", "CH1", "", KEY);

        assertEquals(List.of(searchItem("v1"), searchItem("v2"), searchItem("v3")), result);
        assertEquals(List.of(SEARCH_URL, SEARCH_URL + "&pageToken=page-2"), http.targets());
        assertEquals(List.of(new RecordingLocalStorage.Call(TOOL, "YoutubeAPISearch", "CH1-octopus", RecordingLocalStorage.NO_TTL)), localStorage.calls);
    }

    @Test
    void searchVideosEncodesTheQuery() {
        http.enqueue(FakeJaxRs.response(200, searchPage(null)));

        assertEquals(List.of(), client.searchVideos("release notes&x=1 #2 ü", "CH1", null, KEY));
        assertEquals(List.of("https://www.googleapis.com/youtube/v3/search?part=snippet&maxResults=50&channelId=CH1&q=release+notes%26x%3D1+%232+%C3%BC&type=video&key=" + KEY),
                http.targets());
    }

    @Test
    void searchVideosUsesTheCache() {
        localStorage.prime("YoutubeAPISearch", new YoutubeSearchItem[]{searchItem("cached")});

        assertEquals(List.of(searchItem("cached")), client.searchVideos("octopus", "CH1", "", KEY));
        assertEquals(0, httpCaller.calls());
    }

    @Test
    void searchVideosWrapsApiFailures() {
        http.enqueue(FakeJaxRs.response(500, "error"));

        final RuntimeException ex = assertThrows(RuntimeException.class, () -> client.searchVideos("octopus", "CH1", "", KEY));

        assertEquals("Failed to get Youtube search results", ex.getMessage());
    }

    @Test
    void getTranscriptPrefersTheManualTranscript() {
        transcripts.addManualTranscript("vid", "en", "Hello", "world")
                .addGeneratedTranscript("vid", "en", "generated");

        // Each fragment is prefixed with a space
        assertEquals(" Hello world", client.getTranscript("vid", "en"));
        assertEquals(List.of("list:vid", "manual:vid:en"), transcripts.getCalls());
        assertEquals(List.of("sb_youtube.lock"), mutex.lockNames);
        assertEquals(List.of(new RecordingLocalStorage.Call(TOOL, "YoutubeAPITranscript", "vid-en", RecordingLocalStorage.NO_TTL)), localStorage.calls);
    }

    @Test
    void getTranscriptFallsBackToTheGeneratedTranscript() {
        transcripts.addManualTranscript("vid", "fr", "Bonjour")
                .addGeneratedTranscript("vid", "en", "auto", "text");

        assertEquals(" auto text", client.getTranscript("vid", "en"));
        assertEquals(List.of("list:vid", "manual:vid:en", "generated:vid:en"), transcripts.getCalls());
    }

    @Test
    void getTranscriptDoesNotFallBackWhenTheManualTranscriptFailsToDownload() {
        final IllegalStateException failure = new IllegalStateException("download failed");
        transcripts.failManualFetch("vid", "en", failure)
                .addGeneratedTranscript("vid", "en", "generated");

        assertSame(failure, assertThrows(IllegalStateException.class, () -> client.getTranscript("vid", "en")));
        assertEquals(List.of("list:vid", "manual:vid:en"), transcripts.getCalls());
    }

    @Test
    void getTranscriptThrowsWhenThereIsNoTranscriptInTheLanguage() {
        transcripts.addManualTranscript("vid", "de", "Hallo");

        assertThrows(NoSuchElementException.class, () -> client.getTranscript("vid", "en"));
    }

    @Test
    void getTranscriptPropagatesListingFailures() {
        transcripts.failListingWith(new IllegalStateException("video unavailable"));

        final IllegalStateException ex = assertThrows(IllegalStateException.class, () -> client.getTranscript("vid", "en"));

        assertEquals("video unavailable", ex.getMessage());
    }

    @Test
    void getTranscriptUsesTheCache() {
        localStorage.prime("YoutubeAPITranscript", "cached transcript");

        assertEquals("cached transcript", client.getTranscript("vid", "en"));
        assertEquals(List.of(), transcripts.getCalls());
    }

    @Test
    void getTranscriptThrowsForANullCacheEntry() {
        localStorage.primeNull("YoutubeAPITranscript");

        assertThrows(NoSuchElementException.class, () -> client.getTranscript("vid", "en"));
    }

    @Test
    void getTranscriptUsesTheConfiguredLock() {
        TestConfigUtil.registerConfig(Map.of("sb.youtube.rateLimitPerSecond", "1000", "sb.youtube.lock", "custom.lock"));
        transcripts.addGeneratedTranscript("vid", "en", "text");

        client.getTranscript("vid", "en");

        assertEquals(List.of("custom.lock"), mutex.lockNames);
    }
}
