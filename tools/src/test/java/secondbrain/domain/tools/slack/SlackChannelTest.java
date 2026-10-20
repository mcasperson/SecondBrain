package secondbrain.domain.tools.slack;

import io.smallrye.common.annotation.Identifier;
import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
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
import secondbrain.domain.exceptions.ExternalFailure;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.hooks.NamedHooksContainer;
import secondbrain.domain.hooks.PostInferenceHook;
import secondbrain.domain.hooks.PreProcessingHook;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.limit.DocumentTrimmerExactKeywords;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.objects.ToStringGenerator;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.processing.MockRatingMetadata;
import secondbrain.domain.processing.RatingMetadata;
import secondbrain.domain.processing.RatingToolRatingFilter;
import secondbrain.domain.processing.SentenceVectorizerDataToRagDoc;
import secondbrain.domain.test.FakeSlackClient;
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
import secondbrain.domain.tools.keyword.Keywords;
import secondbrain.domain.validate.ValidateStringBlank;
import secondbrain.infrastructure.llm.LlmClient;
import secondbrain.infrastructure.slack.SlackClient;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link SlackChannel}. Slack is replaced by a {@link FakeSlackClient}, the LLM by a
 * {@link RecordingLlmClient} and the cache by a {@link RecordingLocalStorage}, so no network access is needed.
 */
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(SlackChannel.class)
@AddBeanClasses(SlackChannelConfig.class)
@AddBeanClasses(ArgsAccessorSimple.class)
@AddBeanClasses(ValidateStringBlank.class)
@AddBeanClasses(StandardExceptionMapping.class)
@AddBeanClasses(NamedHooksContainer.class)
@AddBeanClasses(SentenceVectorizerDataToRagDoc.class)
@AddBeanClasses(SimpleSentenceSplitter.class)
@AddBeanClasses(DocumentTrimmerExactKeywords.class)
@AddBeanClasses(RatingToolRatingFilter.class)
@AddBeanClasses(MockRatingMetadata.class)
@AddBeanClasses(Loggers.class)
// Keywords is replaced by a mock from a producer method
@ExcludeBeanClasses(Keywords.class)
class SlackChannelTest {
    private static final String PROMPT = "What happened in the channel?";
    private static final String TOOL = "SlackChannel";
    private static final String TOKEN = "xoxb-token";
    private static final String TO_STRING = "slack-channel";
    private static final int HASH = 11;
    private static final int DEFAULT_HISTORY_TTL = 1000 * 60 * 60 * 24;
    private static final int DEFAULT_API_DELAY = 1000 * 120;
    private static final int DEFAULT_CACHE_TTL = 60 * 60 * 24;
    private static final String FILLER = "filler message. ".repeat(20);

    private final FakeSlackClient slackClient = new FakeSlackClient();
    private final RecordingLlmClient llmClient = new RecordingLlmClient();
    private final RecordingLocalStorage localStorage = new RecordingLocalStorage();

    @Inject
    private SlackChannel tool;

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
    Keywords produceKeywords() {
        return mock(Keywords.class);
    }

    @Produces
    SentenceVectorizer produceSentenceVectorizer() {
        return new StubSentenceVectorizer();
    }

    @Produces
    @Identifier("AES")
    Encryptor produceEncryptor() {
        return TestArgs.encryptor();
    }

    @Produces
    ToStringGenerator produceToStringGenerator() {
        return TestArgs.toStringGenerator(TO_STRING, HASH);
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
        ratingMetadata.setMockRating(null);
    }

    private static Map<String, String> env() {
        return Map.of("slack_access_token", "enc(" + TOKEN + ")");
    }

    /**
     * Messages long enough to pass the minimum message length of 300 characters.
     */
    private static String history(final String text) {
        return text + "\n" + FILLER;
    }

    private static List<ToolArgs> args(final String... nameValues) {
        final List<ToolArgs> arguments = new ArrayList<>();
        for (int i = 0; i < nameValues.length; i += 2) {
            arguments.add(new ToolArgs(nameValues[i], nameValues[i + 1], true));
        }
        return arguments;
    }

    /**
     * Reads the general channel over the last 3 days.
     */
    private static List<ToolArgs> generalArgs(final String... extraNameValues) {
        final List<ToolArgs> arguments = args(SlackChannel.SLACK_CHANEL_ARG, "#general", CommonArguments.DAYS_ARG, "3");
        arguments.addAll(args(extraNameValues));
        return arguments;
    }

    private static String oldest(final int days) {
        return Long.toString(LocalDate.now(ZoneId.systemDefault()).minusDays(days).atStartOfDay(ZoneId.systemDefault()).toEpochSecond());
    }

    private static String historyCall(final String channelId, final int days) {
        return "history:" + TOKEN + ":" + channelId + ":" + oldest(days) + ":" + DEFAULT_HISTORY_TTL + ":" + DEFAULT_API_DELAY + ":[]";
    }

    private static List<String> ids(final List<RagDocumentContext<Void>> context) {
        return context.stream().map(RagDocumentContext::id).toList();
    }

    private static List<String> documents(final List<RagDocumentContext<Void>> context) {
        return context.stream().map(RagDocumentContext::document).toList();
    }

    private void addGeneral(final String text) {
        slackClient.addChannel("T1", "C1", "general", history(text));
    }

    @Test
    void describesItself() {
        assertEquals(TOOL, tool.getName());
        assertEquals("Returns messages from a Slack channel", tool.getDescription());
        assertEquals("Slack Messages", tool.getContextLabel());
        assertEquals("Slack Messages from #ops", tool.getContextLabelWithChannelName("ops"));

        final List<ToolArguments> arguments = tool.getArguments();
        assertEquals(19, arguments.size());
        assertEquals(new ToolArguments(SlackChannel.SLACK_CHANEL_ARG, "The Slack channel to read", "general"), arguments.getFirst());
        assertEquals(new ToolArguments(SlackChannel.TTL_SECONDS_ARG, "The number of seconds to cache the Slack channel results", "86400"), arguments.getLast());
        assertTrue(arguments.stream().map(ToolArguments::name).toList().containsAll(List.of(
                SlackChannel.SLACK_CHANEL_2_ARG, SlackChannel.SLACK_CHANNEL_2_DAYS, CommonArguments.DAYS_ARG,
                SlackChannel.HISTORY_TTL_ARG, SlackChannel.API_DELAY_ARG, CommonArguments.KEYWORDS_ARG)));
    }

    @Test
    void contextHashCodeCombinesArgumentsAndPrompts() {
        assertEquals(31 * HASH + List.of(PROMPT).hashCode(), tool.contextHashCode(env(), List.of(PROMPT), generalArgs()));
    }

    @Test
    void getContextReturnsNothingWithoutChannels() {
        assertEquals(List.of(), tool.getContext(env(), List.of(PROMPT), args(CommonArguments.DAYS_ARG, "3")));
        assertEquals(List.of(), slackClient.calls);
        assertEquals(List.of(), localStorage.calls);
    }

    @Test
    void getContextReturnsTheChannelHistory() {
        addGeneral("The build is broken");

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), generalArgs());

        assertEquals(1, context.size());
        final RagDocumentContext<Void> doc = context.getFirst();
        assertEquals(history("The build is broken"), doc.document());
        assertEquals("C1", doc.id());
        assertEquals(TOOL, doc.tool());
        assertEquals("Slack Messages from #general", doc.contextLabel());
        assertEquals("[Slack general](https://app.slack.com/client/T1/C1)", doc.link());
        assertNull(doc.source());
        assertEquals(List.of("Data-SlackChannel-C1-.txt"), doc.getIntermediateResults().stream().map(IntermediateResult::filename).toList());
        assertEquals(List.of(doc.document()), doc.getIntermediateResults().stream().map(IntermediateResult::content).toList());
        // The channel ID is looked up once for the early exit check and once to read the history
        assertEquals(List.of(
                "findChannelId:" + TOKEN + ":general:" + DEFAULT_API_DELAY,
                "findChannelId:" + TOKEN + ":general:" + DEFAULT_API_DELAY,
                historyCall("C1", 3)), slackClient.calls);
    }

    @Test
    void getContextIncludesTheEntityInTheIntermediateResultFilename() {
        addGeneral("The build is broken");

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), generalArgs(CommonArguments.ENTITY_NAME_CONTEXT_ARG, "Acme"));

        assertEquals(List.of("Data-SlackChannel-C1-Acme.txt"), context.getFirst().getIntermediateResults().stream().map(IntermediateResult::filename).toList());
    }

    @Test
    void getContextPassesTheHistorySettingsToSlack() {
        addGeneral("The build is broken");

        tool.getContext(Map.of("slack_access_token", "plain-token"), List.of(PROMPT), generalArgs(
                SlackChannel.HISTORY_TTL_ARG, "60",
                SlackChannel.API_DELAY_ARG, "5",
                SlackChannel.IGNORED_USERS_ARG, "U9, U8"));

        assertEquals(List.of("history:plain-token:C1:" + oldest(3) + ":60:5:[U9, U8]"), slackClient.callsStartingWith("history:"));
        assertEquals(List.of("findChannelId:plain-token:general:5", "findChannelId:plain-token:general:5"), slackClient.callsStartingWith("findChannelId:"));
    }

    @Test
    void getContextUsesTheConfiguredAccessToken() {
        TestConfigUtil.registerConfig(Map.of("sb.slack.accesstoken", "config-token"));
        addGeneral("The build is broken");

        assertEquals(List.of("C1"), ids(tool.getContext(Map.of(), List.of(PROMPT), generalArgs())));
        assertTrue(slackClient.calls.stream().allMatch(c -> c.contains(":config-token:")));
    }

    @Test
    void getContextReturnsNothingWithoutAnAccessToken() {
        addGeneral("The build is broken");

        assertEquals(List.of(), tool.getContext(Map.of(), List.of(PROMPT), generalArgs()));
        assertEquals(List.of(), slackClient.calls);
    }

    @Test
    void getContextCachesEachChannelUsingTheArgumentsPromptAndChannel() {
        addGeneral("The build is broken");

        tool.getContext(env(), List.of(PROMPT), generalArgs(SlackChannel.TTL_SECONDS_ARG, "120"));

        assertEquals(List.of(new RecordingLocalStorage.Call(TOOL, TOOL, Integer.toString((TO_STRING + PROMPT + "general").hashCode()), 120)),
                localStorage.calls);
    }

    @Test
    void getContextCachesForADayByDefaultAndUsesAnEmptyPromptWhenThereAreNoPrompts() {
        addGeneral("The build is broken");

        assertEquals(List.of("C1"), ids(tool.getContext(env(), List.of(), generalArgs())));

        assertEquals(List.of(new RecordingLocalStorage.Call(TOOL, TOOL, Integer.toString((TO_STRING + "general").hashCode()), DEFAULT_CACHE_TTL)),
                localStorage.calls);
    }

    @Test
    void getContextReturnsCachedDocumentsWithoutReadingTheHistory() {
        addGeneral("The build is broken");
        final RagDocumentContext<Void> cached = new RagDocumentContext<>(TOOL, "Slack Messages", "cached messages", List.of(), "cached");
        localStorage.prime(TOOL, List.of(cached));

        assertEquals(List.of(cached), tool.getContext(env(), List.of(PROMPT), generalArgs()));
        assertEquals(List.of(), slackClient.callsStartingWith("history:"));
    }

    @Test
    void getContextReturnsNothingForANullCachedValue() {
        addGeneral("The build is broken");
        localStorage.primeNull(TOOL);

        assertEquals(List.of(), tool.getContext(env(), List.of(PROMPT), generalArgs()));
    }

    @Test
    void getContextReadsBothChannelsWithTheirOwnLookbackWindows() {
        addGeneral("The build is broken");
        slackClient.addChannel("T1", "C2", "alerts", history("Disk is full"));

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), generalArgs(
                SlackChannel.SLACK_CHANEL_2_ARG, "#alerts",
                SlackChannel.SLACK_CHANNEL_2_DAYS, "1"));

        assertEquals(List.of("C1", "C2"), ids(context));
        assertEquals(List.of("Slack Messages from #general", "Slack Messages from #alerts"), context.stream().map(RagDocumentContext::contextLabel).toList());
        assertEquals(List.of(historyCall("C1", 3), historyCall("C2", 1)), slackClient.callsStartingWith("history:"));
        assertEquals(List.of(
                        Integer.toString((TO_STRING + PROMPT + "general").hashCode()),
                        Integer.toString((TO_STRING + PROMPT + "alerts").hashCode())),
                localStorage.calls.stream().map(RecordingLocalStorage.Call::promptHash).toList());
    }

    @Test
    void getContextReadsTheSecondChannelWithTheFirstChannelsDaysByDefault() {
        addGeneral("The build is broken");
        slackClient.addChannel("T1", "C2", "alerts", history("Disk is full"));

        tool.getContext(env(), List.of(PROMPT), generalArgs(SlackChannel.SLACK_CHANEL_2_ARG, "alerts"));

        assertEquals(List.of(historyCall("C1", 3), historyCall("C2", 3)), slackClient.callsStartingWith("history:"));
    }

    @Test
    void getContextReadsOnlyTheSecondChannelWhenTheFirstIsBlank() {
        slackClient.addChannel("T1", "C2", "alerts", history("Disk is full"));

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args(
                SlackChannel.SLACK_CHANEL_2_ARG, "alerts",
                CommonArguments.DAYS_ARG, "3"));

        assertEquals(List.of("C2"), ids(context));
    }

    @Test
    void getContextSkipsChannelsThatCannotBeFound() {
        slackClient.addChannel("T1", "C2", "alerts", history("Disk is full"));

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), generalArgs(SlackChannel.SLACK_CHANEL_2_ARG, "alerts"));

        assertEquals(List.of("C2"), ids(context));
        assertEquals(List.of(historyCall("C2", 3)), slackClient.callsStartingWith("history:"));
    }

    @Test
    void getContextSkipsChannelsWhoseHistoryCannotBeRead() {
        // The general channel can be found, but reading its history fails
        slackClient.addChannelWithoutHistory("T1", "C1", "general");
        slackClient.addChannel("T1", "C2", "alerts", history("Disk is full"));

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), generalArgs(SlackChannel.SLACK_CHANEL_2_ARG, "alerts"));

        assertEquals(List.of("C2"), ids(context));
        assertEquals(List.of(historyCall("C1", 3), historyCall("C2", 3)), slackClient.callsStartingWith("history:"));
    }

    @Test
    void getContextDropsHistoriesShorterThanTheMinimumLength() {
        slackClient.addChannel("T1", "C1", "general", "a".repeat(299));
        slackClient.addChannel("T1", "C2", "alerts", "b".repeat(300));

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), generalArgs(SlackChannel.SLACK_CHANEL_2_ARG, "alerts"));

        assertEquals(List.of("C2"), ids(context));
        assertEquals(List.of("b".repeat(300)), documents(context));
    }

    @Test
    void getContextAppliesTheMinimumLengthAfterReplacingIds() {
        // The raw history is 300 characters, but replacing the user ID with a short name makes it too short
        final String raw = "<@U12345678>" + "c".repeat(288);
        slackClient.addChannel("T1", "C1", "general", raw);
        slackClient.addUsername("U12345678", "jo");

        assertEquals(300, raw.length());
        assertEquals(List.of(), tool.getContext(env(), List.of(PROMPT), generalArgs()));
    }

    @Test
    void getContextReplacesUserAndChannelIdsWithNames() {
        addGeneral("<@U1> asked <@U2> to check <#C9|> and <#C8>");
        slackClient.addUsername("U1", "jane");
        slackClient.addUsername("U2", "bob");
        slackClient.addChannelName("C9", "alerts");
        slackClient.addChannelName("C8", "ops");

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), generalArgs());

        assertEquals(List.of(history("jane asked bob to check alerts and ops")), documents(context));
        assertEquals(List.of(
                "username:" + TOKEN + ":U1:" + DEFAULT_API_DELAY,
                "username:" + TOKEN + ":U2:" + DEFAULT_API_DELAY), slackClient.callsStartingWith("username:"));
        assertEquals(List.of(
                "channel:" + TOKEN + ":C9:" + DEFAULT_API_DELAY,
                "channel:" + TOKEN + ":C8:" + DEFAULT_API_DELAY), slackClient.callsStartingWith("channel:"));
    }

    @Test
    void getContextKeepsAllUserIdsWhenAnyUsernameLookupFails() {
        addGeneral("<@U1> asked <@U2> to check <#C9|>");
        slackClient.addUsername("U1", "jane");
        slackClient.addChannelName("C9", "alerts");

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), generalArgs());

        // The failed lookup for U2 discards the replacement of U1 too, but channel IDs are still replaced
        assertEquals(List.of(history("<@U1> asked <@U2> to check alerts")), documents(context));
    }

    @Test
    void getContextKeepsChannelIdsWhenAChannelLookupFails() {
        addGeneral("<@U1> asked to check <#C9|>");
        slackClient.addUsername("U1", "jane");

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), generalArgs());

        assertEquals(List.of(history("jane asked to check <#C9|>")), documents(context));
    }

    @Test
    void getContextTrimsTheHistoryToTheKeywords() {
        addGeneral("The outage started at noon");
        slackClient.addChannel("T1", "C2", "alerts", history("Disk is full"));

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), generalArgs(
                SlackChannel.SLACK_CHANEL_2_ARG, "alerts",
                CommonArguments.KEYWORDS_ARG, "outage",
                CommonArguments.KEYWORD_WINDOW_ARG, "40"));

        // The alerts channel has no matching keywords, so trimming leaves it empty and it is dropped
        assertEquals(List.of("C1"), ids(context));
        final String document = context.getFirst().document();
        assertTrue(document.contains("outage"), document);
        assertTrue(document.length() < history("The outage started at noon").length(), document);
        assertEquals(List.of("outage"), context.getFirst().keywordMatches());
    }

    @Test
    void getContextSkipsTheEmptyCheckByDefault() {
        addGeneral("The build is broken");

        tool.getContext(env(), List.of(PROMPT), generalArgs());

        assertEquals(List.of(), slackClient.callsStartingWith("anyItems"));
    }

    @Test
    void getContextSkipsTheHistoryWhenTheEmptyCheckFindsNoItems() {
        addGeneral("The build is broken");
        slackClient.setAnyItems("C1", false);

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), generalArgs(CommonArguments.SKIP_EMPTY_IN_LAST_DURATION, "true"));

        assertEquals(List.of(), context);
        assertEquals(List.of("anyItems:" + TOKEN + ":C1:" + DEFAULT_API_DELAY + ":Years:Months"), slackClient.callsStartingWith("anyItems"));
        assertEquals(List.of(), slackClient.callsStartingWith("history:"));
        assertEquals(List.of(), localStorage.calls);
    }

    @Test
    void getContextReadsTheHistoryWhenTheEmptyCheckFindsItems() {
        addGeneral("The build is broken");
        slackClient.setAnyItems("C1", true);

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), generalArgs(CommonArguments.SKIP_EMPTY_IN_LAST_DURATION, "true"));

        assertEquals(List.of("C1"), ids(context));
        assertEquals(1, slackClient.callsStartingWith("history:").size());
    }

    @Test
    void getContextReadsTheHistoryWhenTheEmptyCheckFails() {
        addGeneral("The build is broken");
        // No anyItems value is set, so the fake throws

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), generalArgs(CommonArguments.SKIP_EMPTY_IN_LAST_DURATION, "true"));

        assertEquals(List.of("C1"), ids(context));
        assertEquals(1, slackClient.callsStartingWith("anyItems").size());
    }

    @Test
    void getContextAppliesPreprocessingHooks() {
        addGeneral("The build is broken");

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), generalArgs(CommonArguments.PREPROCESSOR_HOOKS_ARG, "append,upper"));

        assertEquals(List.of("C1", "append"), ids(context));
        assertEquals(List.of(history("The build is broken").toUpperCase(java.util.Locale.ROOT), "HOOK DOCUMENT"), documents(context));
    }

    @Test
    void getContextAddsThePreinitializationHookDocumentsBeforeTheHistory() {
        addGeneral("The build is broken");

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), generalArgs(CommonArguments.PREINITIALIZATION_HOOKS_ARG, "append"));

        assertEquals(List.of("append", "C1"), ids(context));
        assertEquals(List.of("hook document", history("The build is broken")), documents(context));
    }

    @Test
    void getContextAppliesPreprocessingHooksToThePreinitializationHookDocuments() {
        addGeneral("The build is broken");

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), generalArgs(
                CommonArguments.PREINITIALIZATION_HOOKS_ARG, "append",
                CommonArguments.PREPROCESSOR_HOOKS_ARG, "upper"));

        assertEquals(List.of("append", "C1"), ids(context));
        assertEquals(List.of("HOOK DOCUMENT", history("The build is broken").toUpperCase(java.util.Locale.ROOT)), documents(context));
    }

    @Test
    void getContextFiltersDocumentsBelowTheMinimumRating() {
        addGeneral("The build is broken");
        ratingMetadata.setMockRating(3);

        assertEquals(List.of(), tool.getContext(env(), List.of(PROMPT), generalArgs(CommonArguments.CONTEXT_FILTER_MINIMUM_RATING_ARG, "4")));
    }

    @Test
    void getContextKeepsDocumentsAtTheMinimumRating() {
        addGeneral("The build is broken");
        ratingMetadata.setMockRating(4);

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), generalArgs(CommonArguments.CONTEXT_FILTER_MINIMUM_RATING_ARG, "4"));

        assertEquals(List.of("C1"), ids(context));
        assertEquals(4, context.getFirst().getMetadata().getIntValueByName(RatingToolRatingFilter.FILTER_RATING_META, 0));
    }

    @Test
    void getContextAppliesAnUpperRatingLimit() {
        addGeneral("The build is broken");
        ratingMetadata.setMockRating(8);

        assertEquals(List.of(), tool.getContext(env(), List.of(PROMPT), generalArgs(
                CommonArguments.CONTEXT_FILTER_MINIMUM_RATING_ARG, "5",
                CommonArguments.FILTER_GREATER_THAN_ARG, "true")));
    }

    @Test
    void callAnswersFromTheMessagesAndAppliesPostInferenceHooks() {
        addGeneral("The build is broken");

        final RagMultiDocumentContext<Void> result = tool.call(env(), List.of(PROMPT), generalArgs(CommonArguments.POSTINFERENCE_HOOKS_ARG, "post"));

        assertEquals("answer [post]", result.getResponse());
        assertEquals(1, llmClient.requests.size());
        final RecordingLlmClient.Request request = llmClient.requests.getFirst();
        assertEquals(TOOL, request.tool());
        assertEquals(env(), request.environmentSettings());
        assertEquals(List.of(PROMPT), request.document().getPrompts());
        assertTrue(Objects.requireNonNull(request.document().instructions()).startsWith("You are professional agent that understands Slack conversations."));
        assertEquals(List.of(history("The build is broken")), request.document().getIndividualContexts().stream().map(RagDocumentContext::document).toList());
    }

    @Test
    void callWithoutPostInferenceHooksReturnsTheLlmResponse() {
        addGeneral("The build is broken");

        assertEquals("answer", tool.call(env(), List.of(PROMPT), generalArgs()).getResponse());
    }

    @Test
    void callStillAsksTheLlmWhenThereAreNoMessages() {
        final RagMultiDocumentContext<Void> result = tool.call(env(), List.of(PROMPT), args(CommonArguments.DAYS_ARG, "3"));

        assertEquals("answer", result.getResponse());
        assertEquals(List.of(), llmClient.requests.getFirst().document().getIndividualContexts());
    }

    @Test
    void callPassesInternalFailuresThrough() {
        addGeneral("The build is broken");
        final InternalFailure failure = new InternalFailure("internal");
        llmClient.respondWith(doc -> {
            throw failure;
        });

        assertSame(failure, assertThrows(InternalFailure.class, () -> tool.call(env(), List.of(PROMPT), generalArgs())));
    }

    @Test
    void callPassesExternalFailuresThrough() {
        addGeneral("The build is broken");
        final ExternalFailure failure = new ExternalFailure("external");
        llmClient.respondWith(doc -> {
            throw failure;
        });

        assertSame(failure, assertThrows(ExternalFailure.class, () -> tool.call(env(), List.of(PROMPT), generalArgs())));
    }

    @Test
    void callMapsOtherFailuresToInternalFailures() {
        addGeneral("The build is broken");
        final IllegalStateException failure = new IllegalStateException("network");
        llmClient.respondWith(doc -> {
            throw failure;
        });

        final InternalFailure ex = assertThrows(InternalFailure.class, () -> tool.call(env(), List.of(PROMPT), generalArgs()));

        assertSame(failure, ex.getCause());
    }
}
