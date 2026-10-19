package secondbrain.domain.tools.youtubeplaylist;

import io.smallrye.common.annotation.Identifier;
import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.apache.commons.codec.digest.DigestUtils;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.jboss.weld.junit5.auto.ExcludeBeanClasses;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import secondbrain.domain.args.ArgsAccessorSimple;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.context.SentenceVectorizer;
import secondbrain.domain.context.SimpleSentenceSplitter;
import secondbrain.domain.encryption.Encryptor;
import secondbrain.domain.exceptionhandling.StandardExceptionMapping;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.hooks.NamedHooksContainer;
import secondbrain.domain.hooks.PostInferenceHook;
import secondbrain.domain.hooks.PreProcessingHook;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.limit.DocumentTrimmerExactKeywords;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.objects.ToStringGenerator;
import secondbrain.domain.test.RecordingLlmClient;
import secondbrain.domain.test.StubSentenceVectorizer;
import secondbrain.domain.test.TestArgs;
import secondbrain.domain.test.TestConfigUtil;
import secondbrain.domain.test.TestHooks;
import secondbrain.domain.tooldefs.IntermediateResult;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.tools.CommonArguments;
import secondbrain.domain.tools.keyword.Keywords;
import secondbrain.domain.tools.rating.RatingTool;
import secondbrain.domain.validate.ValidateStringBlank;
import secondbrain.infrastructure.llm.LlmClient;
import secondbrain.infrastructure.youtube.YoutubeClient;
import secondbrain.infrastructure.youtube.api.*;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(YoutubePlaylist.class)
@AddBeanClasses(YoutubeConfig.class)
@AddBeanClasses(ArgsAccessorSimple.class)
@AddBeanClasses(ValidateStringBlank.class)
@AddBeanClasses(DocumentTrimmerExactKeywords.class)
@AddBeanClasses(SimpleSentenceSplitter.class)
@AddBeanClasses(StandardExceptionMapping.class)
@AddBeanClasses(NamedHooksContainer.class)
@AddBeanClasses(Loggers.class)
// The rating and keyword tools are replaced by mocks from producer methods
@ExcludeBeanClasses({RatingTool.class, Keywords.class})
class YoutubePlaylistTest {
    private static final String PROMPT = "What was discussed?";
    private static final String SUMMARY_INSTRUCTIONS_START = "You are a helpful assistant.\nYou are given the summary of list of Youtube video transcripts.";
    private static final String INSTRUCTIONS_START = "You are a helpful assistant.\nYou are given a list of YouTube video transcripts.";

    private final FakeYoutubeClient youtubeClient = new FakeYoutubeClient();
    private final RecordingLlmClient llmClient = new RecordingLlmClient();
    private final RatingTool ratingTool = mock(RatingTool.class);
    private final Keywords keywords = mock(Keywords.class);

    @Inject
    private YoutubePlaylist tool;

    @Produces
    YoutubeClient produceYoutubeClient() {
        return youtubeClient;
    }

    @Produces
    @Preferred
    LlmClient produceLlmClient() {
        return llmClient;
    }

    @Produces
    RatingTool produceRatingTool() {
        return ratingTool;
    }

    @Produces
    Keywords produceKeywords() {
        return keywords;
    }

    @Produces
    SentenceVectorizer produceSentenceVectorizer() {
        return new StubSentenceVectorizer();
    }

    @Produces
    ToStringGenerator produceToStringGenerator() {
        return TestArgs.toStringGenerator("youtube", 5);
    }

    @Produces
    @Identifier("AES")
    Encryptor produceEncryptor() {
        return TestArgs.encryptor();
    }

    @Produces
    PreProcessingHook produceAppendHook() {
        return TestHooks.appendDocument("append", "Hook label", "hook document");
    }

    @Produces
    PreProcessingHook produceUpperCaseHook() {
        return TestHooks.upperCase("upper");
    }

    @Produces
    PostInferenceHook producePostHook() {
        return TestHooks.appendResponse("post", " [post]");
    }

    @BeforeEach
    void setUp() {
        TestConfigUtil.registerConfig(Map.of());
        youtubeClient.addPlaylist("p1", video("v1", "First"), video("v2", "Second"));
        youtubeClient.addPlaylist("p2", video("v3", "Third"));
        youtubeClient.transcripts.put("v1", "first transcript about pricing");
        youtubeClient.transcripts.put("v2", "second transcript about roadmap");
        youtubeClient.transcripts.put("v3", "third transcript about pricing");
    }

    private static Map<String, String> env() {
        return Map.of(YoutubePlaylist.YOUTUBE_API_KEY, "enc(api-key)");
    }

    private static List<ToolArgs> args(final String... nameValues) {
        final List<ToolArgs> arguments = new ArrayList<>();
        for (int i = 0; i < nameValues.length; i += 2) {
            arguments.add(new ToolArgs(nameValues[i], nameValues[i + 1], true));
        }
        return arguments;
    }

    private static YoutubePlaylistsItem video(final String id, final String title) {
        return new YoutubePlaylistsItem("item-" + id, new YoutubePlaylistsItemSnippet(title, new YoutubePlaylistsItemSnippetResourceId("youtube#video", id)));
    }

    private static List<String> documents(final List<RagDocumentContext<Void>> context) {
        return context.stream().map(RagDocumentContext::document).toList();
    }

    private static RagMultiDocumentContext<Void> rating(final String value) {
        return new RagMultiDocumentContext<Void>("rating").updateResponse(value);
    }

    @Test
    void describesItself() {
        assertEquals("YoutubePlaylist", tool.getName());
        assertEquals("Youtube video transcript", tool.getContextLabel());
        assertEquals("Answers a question about the content of a YouTube playlist.", tool.getDescription());
        assertEquals(List.of("playlistId", "channelId", "query", "maxVideos", "keywords", "keywordWindow", "autoGenerateKeywords",
                        "summarizeDocument", "summarizeDocumentPrompt", "contentRatingQuestion", "contextFilterMinimumRating",
                        "defaultRating", "preInitializationHooks", "preProcessorHooks", "postInferenceHooks"),
                tool.getArguments().stream().map(ToolArguments::name).toList());
    }

    @Test
    void getContextReturnsNothingWithoutPlaylistChannelOrQuery() {
        assertEquals(List.of(), tool.getContext(env(), List.of(PROMPT), args()));
        assertTrue(youtubeClient.calls.isEmpty());
    }

    @Test
    void getContextLoadsTranscriptsForEveryVideoInEveryPlaylist() {
        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args(YoutubePlaylist.YOUTUBE_PLAYLIST_ID_ARG, "p1, p2"));

        assertEquals(List.of("first transcript about pricing", "second transcript about roadmap", "third transcript about pricing"), documents(context));
        assertEquals(List.of("playlist:p1:api-key", "playlist:p2:api-key", "transcript:v1:en", "transcript:v2:en", "transcript:v3:en"), youtubeClient.calls);

        final RagDocumentContext<Void> first = context.getFirst();
        assertEquals("YoutubePlaylist", first.tool());
        assertEquals("v1", first.id());
        assertEquals("Youtube video transcript for video titled \"First\" (Video ID: v1)", first.contextLabel());
        assertEquals("[Youtube First](https://www.youtube.com/watch?v=v1)", first.link());
        assertEquals(List.of(new IntermediateResult("first transcript about pricing", "Youtube-v1.txt")), first.getIntermediateResults());
    }

    @Test
    void getContextLimitsTheNumberOfVideos() {
        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT),
                args(YoutubePlaylist.YOUTUBE_PLAYLIST_ID_ARG, "p1,p2", YoutubePlaylist.YOUTUBE_MAX_VIDEOS_ARG, "2"));

        assertEquals(List.of("v1", "v2"), context.stream().map(RagDocumentContext::id).toList());
        assertFalse(youtubeClient.calls.contains("transcript:v3:en"));
    }

    @Test
    void getContextProcessesAtLeastOneVideo() {
        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT),
                args(YoutubePlaylist.YOUTUBE_PLAYLIST_ID_ARG, "p1", YoutubePlaylist.YOUTUBE_MAX_VIDEOS_ARG, "0"));

        assertEquals(List.of("v1"), context.stream().map(RagDocumentContext::id).toList());
    }

    @Test
    void getContextSearchesVideosWhenNoPlaylistIsProvided() {
        youtubeClient.searchResults.add(new YoutubeSearchItem(new YoutubeSearchItemId("v2"), new YoutubeSearchItemSnippet("Found")));

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT),
                args(YoutubePlaylist.YOUTUBE_QUERY_ARG, "roadmap", YoutubePlaylist.YOUTUBE_CHANNEL_ID_ARG, "chan"));

        assertEquals(List.of("search:roadmap:chan:api-key", "transcript:v2:en"), youtubeClient.calls);
        assertEquals("Youtube video transcript for video titled \"Found\" (Video ID: v2)", context.getFirst().contextLabel());
    }

    @Test
    void getContextSearchesByChannelAlone() {
        youtubeClient.searchResults.add(new YoutubeSearchItem(new YoutubeSearchItemId("v1"), new YoutubeSearchItemSnippet("Channel video")));

        assertEquals(1, tool.getContext(env(), List.of(PROMPT), args(YoutubePlaylist.YOUTUBE_CHANNEL_ID_ARG, "chan")).size());
        assertEquals("search::chan:api-key", youtubeClient.calls.getFirst());
    }

    @Test
    void getContextSkipsVideosWhoseTranscriptCannotBeLoaded() {
        youtubeClient.transcripts.remove("v2");

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args(YoutubePlaylist.YOUTUBE_PLAYLIST_ID_ARG, "p1"));

        assertEquals(List.of("v1"), context.stream().map(RagDocumentContext::id).toList());
    }

    @Test
    void getContextReturnsNothingWhenNoTranscriptsAreAvailable() {
        youtubeClient.transcripts.clear();

        assertEquals(List.of(), tool.getContext(env(), List.of(PROMPT), args(YoutubePlaylist.YOUTUBE_PLAYLIST_ID_ARG, "p1")));
    }

    @Test
    void getContextReturnsNothingWhenTheApiKeyIsMissing() {
        assertEquals(List.of(), tool.getContext(Map.of(), List.of(PROMPT), args(YoutubePlaylist.YOUTUBE_PLAYLIST_ID_ARG, "p1")));
        assertTrue(youtubeClient.calls.isEmpty());
    }

    @Test
    void getContextUsesPlainTextOrConfiguredApiKeys() {
        tool.getContext(Map.of(YoutubePlaylist.YOUTUBE_API_KEY, "plain-key"), List.of(PROMPT), args(YoutubePlaylist.YOUTUBE_PLAYLIST_ID_ARG, "p2"));
        assertEquals("playlist:p2:plain-key", youtubeClient.calls.getFirst());
    }

    @Test
    void getContextUsesTheConfiguredApiKeyWhenNoneIsInTheContext() {
        TestConfigUtil.registerConfig(Map.of("sb.google.apiKey", "config-key"));

        tool.getContext(Map.of(), List.of(PROMPT), args(YoutubePlaylist.YOUTUBE_PLAYLIST_ID_ARG, "p2"));

        assertEquals("playlist:p2:config-key", youtubeClient.calls.getFirst());
    }

    @Test
    void getContextTrimsTranscriptsToKeywords() {
        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args(
                YoutubePlaylist.YOUTUBE_PLAYLIST_ID_ARG, "p1",
                CommonArguments.KEYWORDS_ARG, "pricing",
                CommonArguments.KEYWORD_WINDOW_ARG, "20"));

        // The roadmap transcript has no keyword matches, so it is trimmed to nothing and dropped
        assertEquals(List.of("v1"), context.stream().map(RagDocumentContext::id).toList());
        assertEquals(List.of("pricing"), context.getFirst().keywordMatches());
        assertTrue(context.getFirst().document().contains("pricing"));
    }

    @Test
    void getContextFiltersVideosBelowTheMinimumRating() {
        when(ratingTool.call(any(), anyList(), anyList())).thenAnswer(inv -> {
            final Map<String, String> settings = inv.getArgument(0);
            return rating(String.valueOf(settings.get(RatingTool.RATING_DOCUMENT_CONTEXT_ARG)).contains("roadmap") ? "8" : " 3 ");
        });

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args(
                YoutubePlaylist.YOUTUBE_PLAYLIST_ID_ARG, "p1",
                CommonArguments.CONTENT_RATING_QUESTION_ARG, "Is it about the roadmap?",
                CommonArguments.CONTEXT_FILTER_MINIMUM_RATING_ARG, "5"));

        assertEquals(List.of("v2"), context.stream().map(RagDocumentContext::id).toList());
        assertEquals(8, context.getFirst().getMetadata().getIntValueByName(YoutubePlaylist.YOUTUBE_FILTER_RATING_META, -1));
        assertEquals("Youtube-v2.json", context.getFirst().getMetadata().getFilename());
    }

    @Test
    void getContextKeepsVideosRatedExactlyAtTheMinimum() {
        when(ratingTool.call(any(), anyList(), anyList())).thenReturn(rating("5"));

        assertEquals(2, tool.getContext(env(), List.of(PROMPT), args(
                YoutubePlaylist.YOUTUBE_PLAYLIST_ID_ARG, "p1",
                CommonArguments.CONTENT_RATING_QUESTION_ARG, "Relevant?",
                CommonArguments.CONTEXT_FILTER_MINIMUM_RATING_ARG, "5")).size());
    }

    @Test
    void getContextUsesTheDefaultRatingWhenTheRatingCannotBeParsed() {
        when(ratingTool.call(any(), anyList(), anyList())).thenReturn(rating("not a number"));

        final List<ToolArgs> lowDefault = args(
                YoutubePlaylist.YOUTUBE_PLAYLIST_ID_ARG, "p1",
                CommonArguments.CONTENT_RATING_QUESTION_ARG, "Relevant?",
                CommonArguments.CONTEXT_FILTER_MINIMUM_RATING_ARG, "5",
                CommonArguments.DEFAULT_RATING_ARG, "4");
        assertEquals(List.of(), tool.getContext(env(), List.of(PROMPT), lowDefault));

        final List<ToolArgs> highDefault = args(
                YoutubePlaylist.YOUTUBE_PLAYLIST_ID_ARG, "p1",
                CommonArguments.CONTENT_RATING_QUESTION_ARG, "Relevant?",
                CommonArguments.CONTEXT_FILTER_MINIMUM_RATING_ARG, "5");
        assertEquals(2, tool.getContext(env(), List.of(PROMPT), highDefault).size());
    }

    @Test
    void getContextDoesNotRateVideosWithoutAFilterQuestion() {
        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args(
                YoutubePlaylist.YOUTUBE_PLAYLIST_ID_ARG, "p1",
                CommonArguments.CONTEXT_FILTER_MINIMUM_RATING_ARG, "5"));

        assertEquals(2, context.size());
        assertFalse(context.getFirst().getMetadata().hasName(YoutubePlaylist.YOUTUBE_FILTER_RATING_META));
    }

    @Test
    void getContextSummarizesEachTranscriptWhenRequested() {
        llmClient.respondWith(doc -> "summary of " + doc.getIndividualContexts().getFirst().document());

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args(
                YoutubePlaylist.YOUTUBE_PLAYLIST_ID_ARG, "p2",
                CommonArguments.SUMMARIZE_DOCUMENT_ARG, "true",
                CommonArguments.SUMMARIZE_DOCUMENT_PROMPT_ARG, "Summarise briefly"));

        final RagDocumentContext<Void> summary = context.getFirst();
        assertEquals("summary of third transcript about pricing", summary.document());
        assertEquals("Youtube video transcript summary for video titled \"Third\" (Video ID: v3)", summary.contextLabel());
        assertEquals(new IntermediateResult("Prompt: Summarise briefly\n\nsummary of third transcript about pricing",
                "Youtube-v3-" + DigestUtils.sha256Hex("Summarise briefly") + ".txt"), summary.getIntermediateResults().getLast());
        assertEquals(List.of("Summarise briefly"), llmClient.requests.getFirst().document().getPrompts());
        assertEquals("You are a helpful agent", llmClient.requests.getFirst().document().instructions());
    }

    @Test
    void getContextDropsTranscriptsThatFailToSummarize() {
        llmClient.respondWith(doc -> {
            if (doc.getIndividualContexts().getFirst().document().contains("roadmap")) {
                throw new IllegalStateException("LLM failure");
            }
            return "summary";
        });

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args(
                YoutubePlaylist.YOUTUBE_PLAYLIST_ID_ARG, "p1",
                CommonArguments.SUMMARIZE_DOCUMENT_ARG, "true"));

        assertEquals(List.of("v1"), context.stream().map(RagDocumentContext::id).toList());
        assertEquals(List.of("Summarise the Youtube video transcript in three paragraphs"), llmClient.requests.getFirst().document().getPrompts());
    }

    @Test
    void getContextAppliesPreinitializationAndPreprocessingHooks() {
        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args(
                YoutubePlaylist.YOUTUBE_PLAYLIST_ID_ARG, "p2",
                CommonArguments.PREINITIALIZATION_HOOKS_ARG, "append",
                CommonArguments.PREPROCESSOR_HOOKS_ARG, "upper"));

        // The preinitialization document comes first, and the preprocessing hook sees every document
        assertEquals(List.of("HOOK DOCUMENT", "THIRD TRANSCRIPT ABOUT PRICING"), documents(context));
    }

    @Test
    void callSendsTheTranscriptsToTheLlmAndAppliesPostInferenceHooks() {
        final RagMultiDocumentContext<Void> result = tool.call(env(), List.of(PROMPT), args(
                YoutubePlaylist.YOUTUBE_PLAYLIST_ID_ARG, "p2",
                CommonArguments.POSTINFERENCE_HOOKS_ARG, "post"));

        assertEquals("answer [post]", result.getResponse());
        final RecordingLlmClient.Request request = llmClient.requests.getLast();
        assertEquals("YoutubePlaylist", request.tool());
        assertEquals(List.of(PROMPT), request.document().getPrompts());
        assertTrue(Objects.requireNonNull(request.document().instructions()).startsWith(INSTRUCTIONS_START));
        assertEquals(List.of("third transcript about pricing"), request.document().getIndividualContexts().stream().map(RagDocumentContext::document).toList());
    }

    @Test
    void callUsesSummaryInstructionsWhenTranscriptsAreSummarized() {
        llmClient.respondWith(doc -> "summarized");

        tool.call(env(), List.of(PROMPT), args(YoutubePlaylist.YOUTUBE_PLAYLIST_ID_ARG, "p2", CommonArguments.SUMMARIZE_DOCUMENT_ARG, "true"));

        assertTrue(Objects.requireNonNull(llmClient.requests.getLast().document().instructions()).startsWith(SUMMARY_INSTRUCTIONS_START));
    }

    @Test
    void callFailsWithoutPlaylistChannelOrQuery() {
        final InternalFailure ex = assertThrows(InternalFailure.class, () -> tool.call(env(), List.of(PROMPT), args()));

        assertEquals("No playlist ID, channel ID or query provided to YoutubePlaylist tool", ex.getMessage());
        assertTrue(llmClient.requests.isEmpty());
    }

    @Test
    void callMapsLlmFailuresToInternalFailure() {
        llmClient.respondWith(doc -> {
            throw new IllegalStateException("LLM down");
        });

        assertThrows(InternalFailure.class, () -> tool.call(env(), List.of(PROMPT), args(YoutubePlaylist.YOUTUBE_PLAYLIST_ID_ARG, "p2")));
    }

    @Test
    void contextHashCodeCombinesArgumentsAndPrompts() {
        assertEquals(31 * 5 + List.of(PROMPT).hashCode(), tool.contextHashCode(env(), List.of(PROMPT), args()));
    }

    /**
     * Records each call as "operation:arguments" and serves playlists, search results and transcripts from maps.
     * Missing transcripts throw, as the real client does when a video has no captions.
     */
    private static final class FakeYoutubeClient implements YoutubeClient {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final Map<String, List<YoutubePlaylistsItem>> playlists = new HashMap<>();
        final List<YoutubeSearchItem> searchResults = new ArrayList<>();
        final Map<String, String> transcripts = new HashMap<>();

        void addPlaylist(final String id, final YoutubePlaylistsItem... items) {
            playlists.put(id, List.of(items));
        }

        @Override
        public List<YoutubePlaylistsItem> getPlaylistItems(final String playlistId, final String pageToken, final String key) {
            calls.add("playlist:" + playlistId + ":" + key);
            return playlists.getOrDefault(playlistId, List.of());
        }

        @Override
        public List<YoutubeSearchItem> searchVideos(final String query, final String channelId, final String pageToken, final String key) {
            calls.add("search:" + query + ":" + channelId + ":" + key);
            return searchResults;
        }

        @Override
        public String getTranscript(final String videoId, final String lang) {
            calls.add("transcript:" + videoId + ":" + lang);
            final String transcript = transcripts.get(videoId);
            if (transcript == null) {
                throw new IllegalStateException("No transcript for " + videoId);
            }
            return transcript;
        }
    }
}
