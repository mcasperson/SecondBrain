package secondbrain.domain.tools.slack;

import com.slack.api.methods.AsyncMethodsClient;
import io.smallrye.common.annotation.Identifier;
import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import secondbrain.domain.args.ArgsAccessorSimple;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.context.SentenceVectorizer;
import secondbrain.domain.context.SimpleSentenceSplitter;
import secondbrain.domain.date.DateParser;
import secondbrain.domain.date.DateParserUnix;
import secondbrain.domain.encryption.Encryptor;
import secondbrain.domain.exceptions.EmptyString;
import secondbrain.domain.exceptions.ExternalFailure;
import secondbrain.domain.exceptions.FailedOllama;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.hooks.NamedHooksContainer;
import secondbrain.domain.hooks.PostInferenceHook;
import secondbrain.domain.hooks.PreProcessingHook;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.keyword.KeywordExtractor;
import secondbrain.domain.limit.DocumentTrimmerExactKeywords;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.objects.ToStringGenerator;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.processing.MockRatingMetadata;
import secondbrain.domain.processing.RatingMetadata;
import secondbrain.domain.processing.RatingToolRatingFilter;
import secondbrain.domain.processing.SentenceVectorizerDataToRagDoc;
import secondbrain.domain.test.RecordingLlmClient;
import secondbrain.domain.test.RecordingLocalStorage;
import secondbrain.domain.test.StubSentenceVectorizer;
import secondbrain.domain.test.TestArgs;
import secondbrain.domain.test.TestConfigUtil;
import secondbrain.domain.test.TestHooks;
import secondbrain.domain.tooldefs.IntermediateResult;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.tools.CommonArguments;
import secondbrain.domain.validate.ValidateStringBlank;
import secondbrain.infrastructure.llm.LlmClient;
import secondbrain.infrastructure.slack.SlackClient;
import secondbrain.infrastructure.slack.api.SlackChannelResource;
import secondbrain.infrastructure.slack.api.SlackConversationResource;
import secondbrain.infrastructure.slack.api.SlackSearchResultResource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(SlackSearch.class)
@AddBeanClasses(SlackSearchConfig.class)
@AddBeanClasses(ArgsAccessorSimple.class)
@AddBeanClasses(ValidateStringBlank.class)
@AddBeanClasses(SentenceVectorizerDataToRagDoc.class)
@AddBeanClasses(SimpleSentenceSplitter.class)
@AddBeanClasses(DocumentTrimmerExactKeywords.class)
@AddBeanClasses(RatingToolRatingFilter.class)
@AddBeanClasses(MockRatingMetadata.class)
@AddBeanClasses(NamedHooksContainer.class)
@AddBeanClasses(DateParserUnix.class)
@AddBeanClasses(Loggers.class)
// Slack search results are built with null fields to match real responses
@SuppressWarnings("NullAway")
class SlackSearchTest {
    private static final String PROMPT = "What outages happened?";
    private static final String TOOL = "SlackSearch";
    private static final String TOKEN = "xoxb-token";
    private static final int DEFAULT_SEARCH_TTL = 1000 * 60 * 60 * 24 * 6;
    private static final int DEFAULT_API_DELAY = 1000 * 120;

    private final FakeSlackClient slackClient = new FakeSlackClient();
    private final RecordingLlmClient llmClient = new RecordingLlmClient();
    private final RecordingLocalStorage localStorage = new RecordingLocalStorage();
    private final List<String> generatedKeywords = new CopyOnWriteArrayList<>();

    @Inject
    private SlackSearch tool;

    @Inject
    private MockRatingMetadata ratingMetadata;

    @Produces
    @Preferred
    SlackClient produceSlackClient() {
        return slackClient;
    }

    @Produces
    @Preferred
    LlmClient produceLlmClient() {
        return llmClient;
    }

    @Produces
    @Preferred
    LocalStorage produceLocalStorage() {
        return localStorage;
    }

    @Produces
    @Preferred
    RatingMetadata produceRatingMetadata(final MockRatingMetadata mockRatingMetadata) {
        return mockRatingMetadata;
    }

    @Produces
    SentenceVectorizer produceSentenceVectorizer() {
        return new StubSentenceVectorizer();
    }

    @Produces
    KeywordExtractor produceKeywordExtractor() {
        return text -> generatedKeywords;
    }

    @Produces
    @Identifier("AES")
    Encryptor produceEncryptor() {
        return TestArgs.encryptor();
    }

    @Produces
    @Identifier("everything")
    DateParser produceDateParser() {
        return java.time.ZonedDateTime::parse;
    }

    @Produces
    ToStringGenerator produceToStringGenerator() {
        return TestArgs.toStringGenerator("slack-search", 7);
    }

    @Produces
    PreProcessingHook produceAppendHook() {
        return TestHooks.appendDocument("append", "Hook label", "hook outage note");
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
        ratingMetadata.setMockRating(null);
    }

    private static Map<String, String> env() {
        return Map.of("slack_access_token", "enc(" + TOKEN + ")");
    }

    /**
     * The arguments needed to get results: a search keyword, a filter keyword and a days value.
     */
    private static List<ToolArgs> args(final String... extraNameValues) {
        final List<ToolArgs> arguments = new ArrayList<>();
        arguments.add(new ToolArgs(SlackSearch.SLACK_SEARCH_KEYWORDS_ARG, "outage", true));
        arguments.add(new ToolArgs(CommonArguments.KEYWORDS_ARG, "outage", true));
        arguments.add(new ToolArgs(CommonArguments.DAYS_ARG, "0", true));
        arguments.addAll(namedArgs(extraNameValues));
        return arguments;
    }

    private static List<ToolArgs> namedArgs(final String... nameValues) {
        final List<ToolArgs> arguments = new ArrayList<>();
        for (int i = 0; i < nameValues.length; i += 2) {
            arguments.add(new ToolArgs(nameValues[i], nameValues[i + 1], true));
        }
        return arguments;
    }

    /**
     * A search result without a channel name. Only these results are kept by the tool.
     */
    private static SlackSearchResultResource message(final String id, final String text) {
        return new SlackSearchResultResource(id, "1700000000.000100", "", text, "https://slack.test/" + id);
    }

    private static String daysAgo(final int days) {
        return Instant.now().minus(days, ChronoUnit.DAYS).getEpochSecond() + ".000100";
    }

    private static List<String> ids(final List<RagDocumentContext<Void>> context) {
        return context.stream().map(RagDocumentContext::id).toList();
    }

    private static List<String> documents(final List<RagDocumentContext<Void>> context) {
        return context.stream().map(RagDocumentContext::document).toList();
    }

    @Test
    void describesItself() {
        assertEquals(TOOL, tool.getName());
        assertEquals("Slack Messages", tool.getContextLabel());
        assertEquals("Searches Slack for messages that match the prompt", tool.getDescription());
        assertEquals(List.of(SlackSearch.SLACK_SEARCH_KEYWORDS_ARG, CommonArguments.AUTO_GENERATE_KEYWORDS_ARG),
                tool.getArguments().stream().map(ToolArguments::name).toList());
    }

    @Test
    void getContextReturnsMessagesContainingTheKeywords() {
        slackClient.results.add(message("m1", "Big outage in prod"));
        slackClient.results.add(message("m2", "Lunch is ready"));

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args());

        // m2 does not contain the filter keyword, so trimming leaves it empty
        assertEquals(List.of("m1"), ids(context));
        final RagDocumentContext<Void> first = context.getFirst();
        assertEquals("Big outage in prod", first.document());
        assertEquals("Slack Messages", first.contextLabel());
        assertEquals(TOOL, first.tool());
        assertEquals("[Big outage in prod](https://slack.test/m1)", first.link());
        assertEquals(List.of("Data-SlackSearch-m1-.txt"), first.getIntermediateResults().stream().map(IntermediateResult::filename).toList());
        assertEquals(List.of("search:" + TOKEN + ":[outage]:" + DEFAULT_SEARCH_TTL + ":" + DEFAULT_API_DELAY), slackClient.calls);
    }

    @Test
    void getContextCachesTheResultUsingTheArgumentsAndPrompt() {
        slackClient.results.add(message("m1", "outage"));

        tool.getContext(env(), List.of(PROMPT), args(SlackSearch.TTL_SECONDS_ARG, "120"));

        assertEquals(List.of(new RecordingLocalStorage.Call(TOOL, TOOL, Integer.toString(("slack-search\n" + PROMPT).hashCode()), 120)),
                localStorage.calls);
    }

    @Test
    void getContextReturnsCachedDocumentsWithoutSearching() {
        final RagDocumentContext<Void> cached = new RagDocumentContext<>(TOOL, "Slack Messages", "cached outage", List.of(), "cached");
        localStorage.prime(TOOL, List.of(cached));

        assertEquals(List.of(cached), tool.getContext(env(), List.of(PROMPT), args()));
        assertTrue(slackClient.calls.isEmpty());
    }

    @Test
    void getContextPassesTheSearchSettingsToSlack() {
        tool.getContext(Map.of("slack_access_token", "plain-token"), List.of(PROMPT), args(
                SlackSearch.SEARCH_TTL_ARG, "60",
                SlackSearch.API_DELAY_ARG, "5"));

        assertEquals(List.of("search:plain-token:[outage]:60:5"), slackClient.calls);
    }

    @Test
    void getContextDropsMessagesWithAChannelName() {
        slackClient.results.add(new SlackSearchResultResource("named", "1700000000.000100", "general", "outage in general", "https://slack.test/named"));
        slackClient.results.add(message("unnamed", "outage without channel"));

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args());

        // Looks like a bug: the filter keeps messages with a blank channel name, so every message from a named channel is
        // dropped, and the ignoreChannels setting can never match anything
        assertEquals(List.of("unnamed"), ids(context));
    }

    @Test
    void getContextReturnsNothingWhenAMessageHasNoChannel() {
        slackClient.results.add(message("m1", "outage one"));
        slackClient.results.add(new SlackSearchResultResource("m2", "1700000000.000100", null, "outage two", "https://slack.test/m2"));

        // A null channel name fails the ignored channel check, and the failure discards every result
        assertEquals(List.of(), tool.getContext(env(), List.of(PROMPT), args()));
    }

    @Test
    void getContextFiltersMessagesOlderThanTheRequestedDays() {
        slackClient.results.add(new SlackSearchResultResource("recent", daysAgo(1), "", "recent outage", "https://slack.test/recent"));
        slackClient.results.add(new SlackSearchResultResource("old", daysAgo(10), "", "old outage", "https://slack.test/old"));
        slackClient.results.add(new SlackSearchResultResource("undated", "", "", "undated outage", "https://slack.test/undated"));

        final List<ToolArgs> arguments = new ArrayList<>(args());
        arguments.removeIf(arg -> arg.argName().equals(CommonArguments.DAYS_ARG));
        arguments.add(new ToolArgs(CommonArguments.DAYS_ARG, "7", true));

        assertEquals(List.of("recent"), ids(tool.getContext(env(), List.of(PROMPT), arguments)));
    }

    @Test
    void getContextReturnsNothingWithoutADaysArgument() {
        slackClient.results.add(message("m1", "outage"));

        final List<ToolArgs> arguments = namedArgs(SlackSearch.SLACK_SEARCH_KEYWORDS_ARG, "outage", CommonArguments.KEYWORDS_ARG, "outage");

        // Looks like a bug: days has no default, so parsing it fails when filtering results and the failure is
        // swallowed, returning no context even though Slack returned a matching message
        assertEquals(List.of(), tool.getContext(env(), List.of(PROMPT), arguments));
        assertEquals(1, slackClient.calls.size());
    }

    @Test
    void getContextRequiresFilterKeywords() {
        slackClient.results.add(message("m1", "outage"));

        final List<ToolArgs> arguments = namedArgs(SlackSearch.SLACK_SEARCH_KEYWORDS_ARG, "outage", CommonArguments.DAYS_ARG, "0");

        assertEquals(List.of(), tool.getContext(env(), List.of(PROMPT), arguments));
        assertTrue(slackClient.calls.isEmpty());
    }

    @Test
    void getContextRequiresSearchKeywords() {
        slackClient.results.add(message("m1", "outage"));

        final List<ToolArgs> arguments = namedArgs(CommonArguments.KEYWORDS_ARG, "outage", CommonArguments.DAYS_ARG, "0");

        assertEquals(List.of(), tool.getContext(env(), List.of(PROMPT), arguments));
        assertTrue(slackClient.calls.isEmpty());
    }

    @Test
    void getContextUsesGeneratedKeywordsForSearchingAndFiltering() {
        generatedKeywords.add("incident");
        slackClient.results.add(message("m1", "incident report"));

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), namedArgs(
                CommonArguments.AUTO_GENERATE_KEYWORDS_ARG, "true",
                CommonArguments.DAYS_ARG, "0"));

        assertEquals(List.of("m1"), ids(context));
        assertEquals(List.of("search:" + TOKEN + ":[incident]:" + DEFAULT_SEARCH_TTL + ":" + DEFAULT_API_DELAY), slackClient.calls);
    }

    @Test
    void getContextReturnsNothingWhenSearchingFails() {
        slackClient.failSearch = true;

        assertEquals(List.of(), tool.getContext(env(), List.of(PROMPT), args()));
        assertEquals(1, slackClient.calls.size());
    }

    @Test
    void getContextReturnsNothingWhenSlackReturnsNoResults() {
        assertEquals(List.of(), tool.getContext(env(), List.of(PROMPT), args()));
    }

    @Test
    void getContextReturnsNothingWithoutAnAccessToken() {
        slackClient.results.add(message("m1", "outage"));

        assertEquals(List.of(), tool.getContext(Map.of(), List.of(PROMPT), args()));
        assertTrue(slackClient.calls.isEmpty());
    }

    @Test
    void getContextUsesTheConfiguredAccessToken() {
        TestConfigUtil.registerConfig(Map.of("sb.slack.accesstoken", "config-token"));

        tool.getContext(Map.of(), List.of(PROMPT), args());

        assertEquals("search:config-token:[outage]:" + DEFAULT_SEARCH_TTL + ":" + DEFAULT_API_DELAY, slackClient.calls.getFirst());
    }

    @Test
    void getContextSkipsSearchingWhenNothingWasPostedRecently() {
        slackClient.anyItems = false;
        slackClient.results.add(message("m1", "outage"));

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args(CommonArguments.SKIP_EMPTY_IN_LAST_DURATION, "true"));

        assertEquals(List.of(), context);
        assertEquals(List.of("any:" + TOKEN + ":[outage]:" + DEFAULT_API_DELAY + ":Years:Months"), slackClient.calls);
        assertTrue(localStorage.calls.isEmpty());
    }

    @Test
    void getContextSearchesWhenSomethingWasPostedRecently() {
        slackClient.results.add(message("m1", "outage"));

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args(CommonArguments.SKIP_EMPTY_IN_LAST_DURATION, "true"));

        assertEquals(List.of("m1"), ids(context));
        assertEquals(2, slackClient.calls.size());
        assertTrue(slackClient.calls.getFirst().startsWith("any:"));
    }

    @Test
    void getContextAppliesPreinitializationAndPreprocessingHooks() {
        slackClient.results.add(message("m1", "Big outage"));

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args(
                CommonArguments.PREINITIALIZATION_HOOKS_ARG, "append",
                CommonArguments.PREPROCESSOR_HOOKS_ARG, "upper"));

        assertEquals(List.of("HOOK OUTAGE NOTE", "BIG OUTAGE"), documents(context));
    }

    @Test
    void getContextFiltersDocumentsBelowTheMinimumRating() {
        slackClient.results.add(message("m1", "outage"));
        ratingMetadata.setMockRating(3);

        assertEquals(List.of(), tool.getContext(env(), List.of(PROMPT), args(CommonArguments.CONTEXT_FILTER_MINIMUM_RATING_ARG, "4")));
    }

    @Test
    void getContextKeepsDocumentsAtTheMinimumRating() {
        slackClient.results.add(message("m1", "outage"));
        ratingMetadata.setMockRating(4);

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args(CommonArguments.CONTEXT_FILTER_MINIMUM_RATING_ARG, "4"));

        assertEquals(List.of("m1"), ids(context));
        assertEquals(4, context.getFirst().getMetadata().getIntValueByName(RatingToolRatingFilter.FILTER_RATING_META, 0));
    }

    @Test
    void getContextAppliesAnUpperRatingLimit() {
        slackClient.results.add(message("m1", "outage"));
        ratingMetadata.setMockRating(8);

        assertEquals(List.of(), tool.getContext(env(), List.of(PROMPT), args(
                CommonArguments.CONTEXT_FILTER_MINIMUM_RATING_ARG, "5",
                CommonArguments.FILTER_GREATER_THAN_ARG, "true")));
    }

    @Test
    void callAnswersFromTheMessagesAndAppliesPostInferenceHooks() {
        slackClient.results.add(message("m1", "Big outage in prod"));

        final RagMultiDocumentContext<Void> result = tool.call(env(), List.of(PROMPT), args(CommonArguments.POSTINFERENCE_HOOKS_ARG, "post"));

        assertEquals("answer [post]", result.getResponse());
        final RecordingLlmClient.Request request = llmClient.requests.getFirst();
        assertEquals(TOOL, request.tool());
        assertEquals(List.of(PROMPT), request.document().getPrompts());
        assertTrue(Objects.requireNonNull(request.document().instructions()).startsWith("You are professional agent that understands Slack conversations."));
        assertEquals(List.of("Big outage in prod"), request.document().getIndividualContexts().stream().map(RagDocumentContext::document).toList());
    }

    @Test
    void callMapsEmptyStringFailures() {
        llmClient.respondWith(doc -> {
            throw new EmptyString("empty");
        });

        final InternalFailure ex = assertThrows(InternalFailure.class, () -> tool.call(env(), List.of(PROMPT), args()));

        assertEquals("The Slack channel had no matching messages", ex.getMessage());
    }

    @Test
    void callPassesInternalFailuresThrough() {
        final InternalFailure failure = new InternalFailure("internal");
        llmClient.respondWith(doc -> {
            throw failure;
        });

        assertSame(failure, assertThrows(InternalFailure.class, () -> tool.call(env(), List.of(PROMPT), args())));
    }

    @Test
    void callMapsOllamaFailures() {
        llmClient.respondWith(doc -> {
            throw new FailedOllama("model missing");
        });

        final InternalFailure ex = assertThrows(InternalFailure.class, () -> tool.call(env(), List.of(PROMPT), args()));

        assertEquals("model missing", ex.getMessage());
        assertInstanceOf(FailedOllama.class, ex.getCause());
    }

    @Test
    void callMapsOtherFailuresToExternalFailures() {
        llmClient.respondWith(doc -> {
            throw new IllegalStateException("network");
        });

        final ExternalFailure ex = assertThrows(ExternalFailure.class, () -> tool.call(env(), List.of(PROMPT), args()));

        assertEquals("SlackSearch failed to call Ollama", ex.getMessage());
    }

    @Test
    void contextHashCodeCombinesArgumentsAndPrompts() {
        assertEquals(31 * 7 + List.of(PROMPT).hashCode(), tool.contextHashCode(env(), List.of(PROMPT), args()));
    }

    /**
     * Records search calls and returns the configured results.
     */
    private static final class FakeSlackClient implements SlackClient {
        final List<SlackSearchResultResource> results = new ArrayList<>();
        final List<String> calls = new CopyOnWriteArrayList<>();
        boolean failSearch;
        boolean anyItems = true;

        @Override
        public boolean anyItemsInDuration(final AsyncMethodsClient client, final String accessToken, final String channelId, final int apiDelay,
                                          final ChronoUnit duration, final ChronoUnit cached) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean anyItemsInDuration(final AsyncMethodsClient client, final String accessToken, final Set<String> keywords, final int apiDelay,
                                          final ChronoUnit duration, final ChronoUnit cached) {
            calls.add("any:" + accessToken + ":" + keywords + ":" + apiDelay + ":" + duration + ":" + cached);
            return anyItems;
        }

        @Override
        public String conversationHistory(final AsyncMethodsClient client, final String accessToken, final String channelId, final String oldest,
                                          final int ttlSeconds, final int apiDelay, final List<String> ignoredUsers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String username(final AsyncMethodsClient client, final String accessToken, final String userId, final int apiDelay) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SlackConversationResource channel(final AsyncMethodsClient client, final String accessToken, final String channelId, final int apiDelay) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<SlackSearchResultResource> search(final AsyncMethodsClient client, final String accessToken, final Set<String> keywords,
                                                      final int ttlSeconds, final int apiDelay) {
            calls.add("search:" + accessToken + ":" + keywords + ":" + ttlSeconds + ":" + apiDelay);
            if (failSearch) {
                throw new IllegalStateException("Slack unavailable");
            }
            return results;
        }

        @Override
        public SlackChannelResource findChannelId(final AsyncMethodsClient client, final String accessToken, final String channel, final int apiDelay) {
            throw new UnsupportedOperationException();
        }
    }
}
