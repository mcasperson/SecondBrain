package secondbrain.domain.tools.slackzengoogle;

import io.smallrye.common.annotation.Identifier;
import io.smallrye.config.inject.ConfigExtension;
import io.vavr.control.Try;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.jboss.weld.junit5.auto.ExcludeBeanClasses;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import secondbrain.domain.args.ArgsAccessorSimple;
import secondbrain.domain.concurrency.SharedVirtualThreadExecutor;
import secondbrain.domain.context.HashMapEnvironmentSettings;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.exceptionhandling.ExceptionMapping;
import secondbrain.domain.exceptionhandling.LoggingExceptionHandler;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.json.JsonDeserializerJackson;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.objects.ToStringGenerator;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.exceptions.ExternalFailure;
import secondbrain.domain.exceptions.InsufficientContext;
import secondbrain.domain.reader.FileReader;
import secondbrain.domain.sanitize.SanitizeDocument;
import secondbrain.domain.test.RecordingLocalStorage;
import secondbrain.domain.test.TestArgs;
import secondbrain.domain.test.TestConfigUtil;
import secondbrain.domain.validate.ValidateStringBlank;
import secondbrain.domain.tooldefs.MetaObjectResult;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.tools.CommonArguments;
import secondbrain.domain.tools.gong.Gong;
import secondbrain.domain.tools.googledocs.GoogleDocs;
import secondbrain.domain.tools.keyword.Keywords;
import secondbrain.domain.tools.planhat.PlanHat;
import secondbrain.domain.tools.planhat.PlanHatUsage;
import secondbrain.domain.tools.rating.RatingTool;
import secondbrain.domain.tools.salesforce.Salesforce;
import secondbrain.domain.tools.salesforce.SalesforceAccountDetails;
import secondbrain.domain.tools.slack.SlackChannel;
import secondbrain.domain.tools.slack.SlackSearch;
import secondbrain.domain.tools.zendesk.ZenDeskOrganization;
import secondbrain.domain.yaml.YamlDeserializerJackson;
import secondbrain.infrastructure.llm.LlmClient;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MultiSlackZenGoogle} with every downstream tool replaced by a mock, so no external
 * services, CDI container or LLM are needed.
 */
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(MultiSlackZenGoogle.class)
@AddBeanClasses(MultiSlackZenGoogleConfig.class)
@AddBeanClasses(ArgsAccessorSimple.class)
@AddBeanClasses(ValidateStringBlank.class)
@AddBeanClasses(YamlDeserializerJackson.class)
@AddBeanClasses(JsonDeserializerJackson.class)
@AddBeanClasses(SharedVirtualThreadExecutor.class)
@AddBeanClasses(LoggingExceptionHandler.class)
@AddBeanClasses(Loggers.class)
// The downstream tools are replaced by mocks from producer methods
@ExcludeBeanClasses({SlackChannel.class, SlackSearch.class, GoogleDocs.class, PlanHat.class, PlanHatUsage.class,
        ZenDeskOrganization.class, Gong.class, Salesforce.class, SalesforceAccountDetails.class, RatingTool.class, Keywords.class})
class MultiSlackZenGoogleUnitTest {
    private static final String DIRECTORY_URL = "https://example.org/directory.yaml";
    private static final String PROMPT = "What happened?";
    private static final String ACME_ONLY = """
            entities:
              - name: Acme
                zendesk: [z1]
                slack: [s1]
                googledocs: [g1]
                planhat: [p1]
                salesforce: [sf1]
            """;

    private final Map<String, String> files = new HashMap<>(Map.of(DIRECTORY_URL, ACME_ONLY));
    private final RecordingLocalStorage localStorage = new RecordingLocalStorage();
    private final AtomicReference<RagMultiDocumentContext<?>> llmRequest = new AtomicReference<>();

    private final SlackChannel slackChannel = labelled(SlackChannel.class, "Slack Messages");
    private final SlackSearch slackSearch = labelled(SlackSearch.class, "Slack Search");
    private final GoogleDocs googleDocs = labelled(GoogleDocs.class, "Google Document");
    private final PlanHat planHat = labelled(PlanHat.class, "PlanHat Activity");
    private final PlanHatUsage planHatUsage = labelled(PlanHatUsage.class, "PlanHat Usage");
    private final ZenDeskOrganization zenDesk = labelled(ZenDeskOrganization.class, "ZenDesk Ticket");
    private final Gong gong = labelled(Gong.class, "Gong Call");
    private final Salesforce salesforce = labelled(Salesforce.class, "Salesforce Email");
    private final SalesforceAccountDetails salesforceAccountDetails = labelled(SalesforceAccountDetails.class, "Salesforce Account Details");
    private final RatingTool ratingTool = mock(RatingTool.class);
    private final LlmClient llmClient = mock(LlmClient.class);

    @Inject
    private MultiSlackZenGoogle tool;

    @Produces
    SlackChannel produceSlackChannel() {
        return slackChannel;
    }

    @Produces
    SlackSearch produceSlackSearch() {
        return slackSearch;
    }

    @Produces
    GoogleDocs produceGoogleDocs() {
        return googleDocs;
    }

    @Produces
    PlanHat producePlanHat() {
        return planHat;
    }

    @Produces
    PlanHatUsage producePlanHatUsage() {
        return planHatUsage;
    }

    @Produces
    ZenDeskOrganization produceZenDesk() {
        return zenDesk;
    }

    @Produces
    Gong produceGong() {
        return gong;
    }

    @Produces
    Salesforce produceSalesforce() {
        return salesforce;
    }

    @Produces
    SalesforceAccountDetails produceSalesforceAccountDetails() {
        return salesforceAccountDetails;
    }

    @Produces
    RatingTool produceRatingTool() {
        return ratingTool;
    }

    @Produces
    Keywords produceKeywords() {
        return mock(Keywords.class);
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
    FileReader produceFileReader() {
        return path -> Optional.ofNullable(files.get(path)).orElseThrow(() -> new IllegalArgumentException("No file " + path));
    }

    @Produces
    ToStringGenerator produceToStringGenerator() {
        return TestArgs.toStringGenerator("cfg", 11);
    }

    @Produces
    @Identifier("findFirstMarkdownBlock")
    SanitizeDocument produceFindFirstMarkdownBlock() {
        return new CodeFenceExtractor();
    }

    @Produces
    ExceptionMapping produceExceptionMapping() {
        return new IdentityExceptionMapping();
    }

    @BeforeEach
    void setUp() {
        TestConfigUtil.registerConfig(Map.of());
        when(llmClient.callWithCache(any(), any(), any())).thenAnswer(inv -> {
            final RagMultiDocumentContext<?> request = inv.getArgument(0);
            llmRequest.set(request);
            return request.updateResponse("answer");
        });
    }

    private static <T> T labelled(final Class<T> clazz, final String label) {
        final T mock = mock(clazz);
        try {
            when(clazz.getMethod("getContextLabel").invoke(mock)).thenReturn(label);
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return mock;
    }

    private static RagDocumentContext<Void> doc(final String toolName, final String label, final String text) {
        return new RagDocumentContext<>(toolName, label, text, List.of());
    }

    private static List<ToolArgs> args(final String... nameValues) {
        final List<ToolArgs> arguments = new ArrayList<>();
        arguments.add(new ToolArgs(MultiSlackZenGoogle.MULTI_SLACK_ZEN_URL_ARG, DIRECTORY_URL, true));
        for (int i = 0; i < nameValues.length; i += 2) {
            arguments.add(new ToolArgs(nameValues[i], nameValues[i + 1], true));
        }
        return arguments;
    }

    private static Map<String, String> asMap(final List<ToolArgs> arguments) {
        return arguments.stream().collect(Collectors.toMap(ToolArgs::argName, ToolArgs::argValue, (a, b) -> b));
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<ToolArgs>> argsCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, String>> envCaptor() {
        return ArgumentCaptor.forClass(Map.class);
    }

    private void stubAllSources() {
        when(gong.getContext(any(), any(), any())).thenReturn(List.of(doc("Gong", "Gong Call", "call notes")));
        when(zenDesk.getContext(any(), any(), any())).thenReturn(List.of(doc("ZenDeskOrganization", "ZenDesk Ticket", "ticket text")));
        when(slackChannel.getContext(any(), any(), any())).thenReturn(List.of(doc("SlackChannel", "Slack Messages from #s1", "slack text")));
        when(googleDocs.getContext(any(), any(), any())).thenReturn(List.of(doc("GoogleDocs", "Google Document", "doc text")));
        when(planHat.getContext(any(), any(), any())).thenReturn(List.of(doc("PlanHat", "PlanHat Activity", "activity")));
        when(salesforce.getContext(any(), any(), any())).thenReturn(List.of(doc("Salesforce", "Salesforce Email", "email")));
        when(salesforceAccountDetails.getContext(any(), any(), any())).thenReturn(List.of(doc("SalesforceAccountDetails", "Salesforce Account Details", "account")));
        when(planHatUsage.getContext(any(), any(), any())).thenReturn(List.of(doc("PlanHatUsage", "PlanHat Usage", "usage")));
        when(slackSearch.getContext(any(), any(), any())).thenReturn(List.of(doc("SlackSearch", "Slack Search", "search hit")));
    }

    @Test
    void describesItselfAsTheEntityDirectoryTool() {
        assertEquals("MultiSlackZenGoogle", tool.getName());
        assertEquals("Unused", tool.getContextLabel());
        assertTrue(tool.getDescription().contains("entity directory"));
        assertEquals(List.of("url", "keywords", "keywordWindow", "autoGenerateKeywords", "entityName", "maxEntities", "days",
                        "minimumContentLength", "requireCompany"),
                tool.getArguments().stream().map(ToolArguments::name).toList());
    }

    @Test
    void getContextFailsWhenDirectoryCannotBeRead() {
        files.clear();

        final ExternalFailure ex = assertThrows(ExternalFailure.class, () -> tool.getContext(Map.of(), List.of(PROMPT), args()));

        assertEquals("Failed to download or parse the entity directory", ex.getMessage());
    }

    @Test
    void getContextQueriesEverySourceAndPrefixesLabelsWithEntityName() {
        stubAllSources();

        final List<RagDocumentContext<Void>> context = tool.getContext(Map.of(), List.of(PROMPT), args());

        // Sorted by tool name; the slack search runs once for each salesforce and planhat id
        assertEquals(List.of("Gong", "GoogleDocs", "PlanHat", "PlanHatUsage", "Salesforce", "SalesforceAccountDetails", "SlackChannel", "SlackSearch", "SlackSearch", "ZenDeskOrganization"),
                context.stream().map(RagDocumentContext::tool).toList());
        assertEquals("Acme Slack Messages from #s1",
                context.stream().filter(c -> c.tool().equals("SlackChannel")).findFirst().orElseThrow().contextLabel());
        assertTrue(context.stream().allMatch(c -> c.contextLabel().startsWith("Acme ")));
    }

    @Test
    void getContextPassesEntityIdsAndSharedSettingsToEachSource() {
        stubAllSources();

        tool.getContext(Map.of("existing", "setting"), List.of(PROMPT), args(
                CommonArguments.KEYWORDS_ARG, "renewal,churn",
                CommonArguments.DAYS_ARG, "14",
                CommonArguments.SUMMARIZE_DOCUMENT_PROMPT_ARG, "summarise"));

        final ArgumentCaptor<Map<String, String>> env = envCaptor();
        final ArgumentCaptor<List<ToolArgs>> slackArgs = argsCaptor();
        verify(slackChannel).getContext(env.capture(), eq(List.of(PROMPT)), slackArgs.capture());
        assertEquals("Acme", env.getValue().get(CommonArguments.ENTITY_NAME_CONTEXT_ARG));
        assertEquals("setting", env.getValue().get("existing"));
        assertEquals("MultiSlackZenGoogle[Acme]", env.getValue().get(HashMapEnvironmentSettings.TOOL_CALLS));
        final Map<String, String> slack = asMap(slackArgs.getValue());
        assertEquals("s1", slack.get(SlackChannel.SLACK_CHANEL_ARG));
        assertEquals("renewal,churn", slack.get(CommonArguments.KEYWORDS_ARG));
        assertEquals("14", slack.get(CommonArguments.DAYS_ARG));
        assertEquals("true", slack.get(CommonArguments.SUMMARIZE_DOCUMENT_ARG));
        assertEquals("summarise", slack.get(CommonArguments.SUMMARIZE_DOCUMENT_PROMPT_ARG));

        final ArgumentCaptor<List<ToolArgs>> zenArgs = argsCaptor();
        verify(zenDesk).getContext(any(), any(), zenArgs.capture());
        assertEquals("z1", asMap(zenArgs.getValue()).get(ZenDeskOrganization.ZENDESK_ORGANIZATION_ARG));

        final ArgumentCaptor<List<ToolArgs>> gongArgs = argsCaptor();
        verify(gong).getContext(any(), any(), gongArgs.capture());
        assertEquals("sf1", asMap(gongArgs.getValue()).get(Gong.COMPANY_ARG));

        final ArgumentCaptor<List<ToolArgs>> salesforceArgs = argsCaptor();
        verify(salesforce).getContext(any(), any(), salesforceArgs.capture());
        assertEquals("sf1", asMap(salesforceArgs.getValue()).get(Salesforce.ACCOUNT_ID));

        final ArgumentCaptor<List<ToolArgs>> accountDetailsArgs = argsCaptor();
        verify(salesforceAccountDetails).getContext(any(), any(), accountDetailsArgs.capture());
        assertEquals(Map.of(SalesforceAccountDetails.ACCOUNT_ID_ARG, "sf1"), asMap(accountDetailsArgs.getValue()));

        final ArgumentCaptor<List<ToolArgs>> planHatArgs = argsCaptor();
        verify(planHat).getContext(any(), any(), planHatArgs.capture());
        assertEquals("p1", asMap(planHatArgs.getValue()).get(PlanHat.COMPANY_ID_ARGS));

        final ArgumentCaptor<List<ToolArgs>> usageArgs = argsCaptor();
        verify(planHatUsage).getContext(any(), any(), usageArgs.capture());
        assertEquals("p1", asMap(usageArgs.getValue()).get(PlanHatUsage.COMPANY_ID_ARGS));

        final ArgumentCaptor<List<ToolArgs>> googleArgs = argsCaptor();
        verify(googleDocs).getContext(any(), any(), googleArgs.capture());
        assertEquals("g1", asMap(googleArgs.getValue()).get(GoogleDocs.GOOGLE_DOC_ID_ARG));

        final ArgumentCaptor<List<ToolArgs>> searchArgs = argsCaptor();
        verify(slackSearch, times(2)).getContext(any(), any(), searchArgs.capture());
        assertEquals(Set.of("sf1", "p1"), searchArgs.getAllValues().stream()
                .map(a -> asMap(a).get(SlackSearch.SLACK_SEARCH_KEYWORDS_ARG))
                .collect(Collectors.toSet()));
    }

    @Test
    void getContextOnlyProcessesTheRequestedEntity() {
        files.put(DIRECTORY_URL, """
                entities:
                  - name: Acme
                    zendesk: [z-acme]
                  - name: Globex
                    zendesk: [z-globex]
                """);
        when(zenDesk.getContext(any(), any(), any())).thenReturn(List.of(doc("ZenDeskOrganization", "ZenDesk Ticket", "ticket")));

        final List<RagDocumentContext<Void>> context = tool.getContext(Map.of(), List.of(PROMPT), args(CommonArguments.ENTITY_NAME_CONTEXT_ARG, "GLOBEX"));

        assertEquals(List.of("Globex ZenDesk Ticket"), context.stream().map(RagDocumentContext::contextLabel).toList());
        final ArgumentCaptor<List<ToolArgs>> zenArgs = argsCaptor();
        verify(zenDesk).getContext(any(), any(), zenArgs.capture());
        assertEquals("z-globex", asMap(zenArgs.getValue()).get(ZenDeskOrganization.ZENDESK_ORGANIZATION_ARG));
    }

    @Test
    void getContextHonoursConfiguredMaximumNumberOfEntities() {
        files.put(DIRECTORY_URL, """
                entities:
                  - name: Acme
                    zendesk: [z-acme]
                  - name: Globex
                    zendesk: [z-globex]
                """);
        TestConfigUtil.registerConfig(Map.of("sb.multislackzengoogle.maxentities", "1"));
        when(zenDesk.getContext(any(), any(), any())).thenReturn(List.of(doc("ZenDeskOrganization", "ZenDesk Ticket", "ticket")));

        assertEquals(1, tool.getContext(Map.of(), List.of(PROMPT), args()).size());
        verify(zenDesk, times(1)).getContext(any(), any(), any());
    }

    @Test
    void getContextSkipsDisabledEntities() {
        files.put(DIRECTORY_URL, """
                entities:
                  - name: Acme
                    zendesk: [z1]
                    disabled: true
                """);

        assertThrows(InsufficientContext.class, () -> tool.getContext(Map.of(), List.of(PROMPT), args()));
        verifyNoInteractions(zenDesk);
    }

    @Test
    void entitiesWithoutTimeBasedContextAreDroppedByDefault() {
        files.put(DIRECTORY_URL, """
                entities:
                  - name: Acme
                    googledocs: [g1]
                """);
        when(googleDocs.getContext(any(), any(), any())).thenReturn(List.of(doc("GoogleDocs", "Google Document", "static doc")));

        assertThrows(InsufficientContext.class, () -> tool.getContext(Map.of(), List.of(PROMPT), args()));

        final List<RagDocumentContext<Void>> relaxed = tool.getContext(Map.of(), List.of(PROMPT),
                args(MultiSlackZenGoogle.MULTI_SLACK_ZEN_MIN_TIME_BASED_CONTENT_ARG, "0"));
        assertEquals(List.of("Acme Google Document"), relaxed.stream().map(RagDocumentContext::contextLabel).toList());
    }

    @Test
    void salesforceAccountDetailsAreNotTimeBasedContext() {
        files.put(DIRECTORY_URL, """
                entities:
                  - name: Acme
                    salesforce: [sf1]
                """);
        when(salesforceAccountDetails.getContext(any(), any(), any())).thenReturn(List.of(doc("SalesforceAccountDetails", "Salesforce Account Details", "account")));

        assertThrows(InsufficientContext.class, () -> tool.getContext(Map.of(), List.of(PROMPT), args()));

        final List<RagDocumentContext<Void>> relaxed = tool.getContext(Map.of(), List.of(PROMPT),
                args(MultiSlackZenGoogle.MULTI_SLACK_ZEN_MIN_TIME_BASED_CONTENT_ARG, "0"));
        assertEquals(List.of("Acme Salesforce Account Details"), relaxed.stream().map(RagDocumentContext::contextLabel).toList());
    }

    @Test
    void failingSourceDoesNotPreventOtherSourcesFromContributing() {
        stubAllSources();
        when(slackChannel.getContext(any(), any(), any())).thenThrow(new ExternalFailure("slack is down"));
        when(gong.getContext(any(), any(), any())).thenThrow(new IllegalStateException("gong bug"));

        final List<String> tools = tool.getContext(Map.of(), List.of(PROMPT), args()).stream().map(RagDocumentContext::tool).toList();

        assertFalse(tools.contains("SlackChannel"));
        assertFalse(tools.contains("Gong"));
        assertTrue(tools.contains("ZenDeskOrganization"));
        assertTrue(tools.contains("Salesforce"));
    }

    @Test
    void callSendsContextToLlmWithInstructionsForMissingSources() {
        files.put(DIRECTORY_URL, """
                entities:
                  - name: Acme
                    zendesk: [z1]
                """);
        when(zenDesk.getContext(any(), any(), any())).thenReturn(List.of(doc("ZenDeskOrganization", "ZenDesk Ticket", "ticket text")));

        final RagMultiDocumentContext<Void> result = tool.call(Map.of(), List.of(PROMPT), args(
                MultiSlackZenGoogle.MULTI_SLACK_ZEN_ADDITIONAL_SYSTEM_PROMPT, "Be brief.",
                MultiSlackZenGoogle.MULTI_SLACK_ZEN_MAX_ANNOTATION_PREFIX_ARG, "z"));

        assertEquals(List.of("answer"), result.getResponses());
        final RagMultiDocumentContext<?> request = llmRequest.get();
        assertNotNull(request);
        final String instructions = Objects.requireNonNull(request.instructions());
        assertTrue(instructions.contains("No Slack Messages are available."));
        assertTrue(instructions.contains("No Google Document are available."));
        assertTrue(instructions.contains("No PlanHat Activity are available."));
        assertTrue(instructions.contains("No Gong Call are available."));
        assertTrue(instructions.contains("No Salesforce Email are available."));
        assertFalse(instructions.contains("No ZenDesk Ticket are available."));
        assertTrue(instructions.endsWith("\nBe brief."));
        assertEquals("z", request.getAnnotationPrefix());
        assertEquals(List.of(PROMPT), request.getPrompts());
        assertEquals(Optional.of(new MetaObjectResult("ContextCount", 1, null, "MultiSlackZenGoogle")),
                request.getMetadata().getByName("ContextCount"));
        verify(llmClient).callWithCache(any(), eq(Map.of()), eq("MultiSlackZenGoogle"));
    }

    @Test
    void callCachesResultUsingConfigAndPromptsAsKey() {
        files.put(DIRECTORY_URL, """
                entities:
                  - name: Acme
                    zendesk: [z1]
                """);
        when(zenDesk.getContext(any(), any(), any())).thenReturn(List.of(doc("ZenDeskOrganization", "ZenDesk Ticket", "ticket text")));

        tool.call(Map.of(), List.of(PROMPT), args(MultiSlackZenGoogle.MULTI_SLACK_ZEN_EXCLUDE_CACHE_GETTER_ARG, "getUrl,getDays"));

        assertEquals(List.of(new RecordingLocalStorage.Call("MultiSlackZenGoogle", "MultiSlackZenGoogleV2",
                Integer.toString(("cfg[geturl, getdays]_" + PROMPT).hashCode()), 60 * 60 * 24)), localStorage.calls);
    }

    @Test
    void callReturnsCachedResultWithoutQueryingSources() {
        final RagMultiDocumentContext<Void> cached = new RagMultiDocumentContext<Void>(List.of(PROMPT)).updateResponse("cached answer");
        localStorage.prime("MultiSlackZenGoogleV2", cached);

        assertEquals(cached, tool.call(Map.of(), List.of(PROMPT), args()));
        verifyNoInteractions(zenDesk, llmClient);
    }

    @Test
    void callStripsMarkdownCodeBlockWhenRequested() {
        files.put(DIRECTORY_URL, """
                entities:
                  - name: Acme
                    zendesk: [z1]
                """);
        when(zenDesk.getContext(any(), any(), any())).thenReturn(List.of(doc("ZenDeskOrganization", "ZenDesk Ticket", "ticket text")));
        doAnswer(inv -> ((RagMultiDocumentContext<?>) inv.getArgument(0)).updateResponse("Here you go:\n```json\n{\"ok\":true}\n```"))
                .when(llmClient).callWithCache(any(), any(), any());

        final RagMultiDocumentContext<Void> result = tool.call(Map.of(), List.of(PROMPT),
                args(MultiSlackZenGoogle.MULTI_SLACK_ZEN_STRIP_MARKDOWN_CODE_BLOCK, "true"));

        assertEquals(List.of("{\"ok\":true}"), result.getResponses());
    }

    @Test
    void callExplainsWhenNoContextWasFound() {
        files.put(DIRECTORY_URL, """
                entities:
                  - name: Acme
                    zendesk: [z1]
                """);

        final RagMultiDocumentContext<Void> result = tool.call(Map.of(), List.of(PROMPT), args());

        assertEquals(List.of("InsufficientContext: No Salesforce emails, ZenDesk tickets, Slack messages, or PlanHat activities found."),
                result.getResponses());
        verifyNoInteractions(llmClient);
    }

    @Test
    void callAddsRatedMetaFieldsAndDefaultsFailedRatingsToZero() {
        files.put(DIRECTORY_URL, """
                entities:
                  - name: Acme
                    zendesk: [z1]
                    slack: [s1]
                """);
        when(zenDesk.getContext(any(), any(), any())).thenReturn(List.of(doc("ZenDeskOrganization", "ZenDesk Ticket", "ticket text")));
        when(slackChannel.getContext(any(), any(), any())).thenReturn(List.of(doc("SlackChannel", "Slack Messages", "slack text")));
        when(ratingTool.call(any(), eq(List.of("rate sentiment")), any()))
                .thenReturn(new RagMultiDocumentContext<Void>(List.of("rate sentiment")).updateResponse(" 7 "));
        when(ratingTool.call(any(), eq(List.of("rate risk")), any()))
                .thenReturn(new RagMultiDocumentContext<Void>(List.of("rate risk")).updateResponse("high"));

        tool.call(Map.of(), List.of(PROMPT), args(
                MultiSlackZenGoogle.MULTI_SLACK_ZEN_META_FIELD_1_ARG, "Sentiment",
                MultiSlackZenGoogle.MULTI_SLACK_ZEN_META_PROMPT_1_ARG, "rate sentiment",
                MultiSlackZenGoogle.MULTI_SLACK_ZEN_META_FIELD_2_ARG, "Risk",
                MultiSlackZenGoogle.MULTI_SLACK_ZEN_META_PROMPT_2_ARG, "rate risk",
                MultiSlackZenGoogle.MULTI_SLACK_ZEN_META_FIELD_3_ARG, "NoPrompt"));

        final RagMultiDocumentContext<?> request = Objects.requireNonNull(llmRequest.get());
        assertEquals(Optional.of(new MetaObjectResult("Sentiment", 7, null, "MultiSlackZenGoogle")), request.getMetadata().getByName("Sentiment"));
        assertEquals(Optional.of(new MetaObjectResult("Risk", 0, null, "MultiSlackZenGoogle")), request.getMetadata().getByName("Risk"));
        assertFalse(request.getMetadata().hasName("NoPrompt"), "fields without a prompt are not rated");

        @SuppressWarnings("unchecked") final ArgumentCaptor<Map<String, String>> ratingInput = ArgumentCaptor.forClass(Map.class);
        verify(ratingTool).call(ratingInput.capture(), eq(List.of("rate sentiment")), any());
        final String rated = String.valueOf(ratingInput.getValue().get(RatingTool.RATING_DOCUMENT_CONTEXT_ARG));
        assertTrue(rated.contains("ticket text") && rated.contains("slack text"), rated);
    }

    @Test
    void contextHashCodeCombinesConfigHashAndPrompts() {
        assertEquals(31 * 11 + List.of(PROMPT).hashCode(), tool.contextHashCode(Map.of(), List.of(PROMPT), args()));
    }

    private static final class IdentityExceptionMapping implements ExceptionMapping {
        @Override
        public <T> Try<T> map(final Try<T> tryObject) {
            return tryObject;
        }
    }

    /**
     * Returns the contents of the first fenced code block, or the original text when there is none.
     */
    private static final class CodeFenceExtractor implements SanitizeDocument {
        @Override
        public @Nullable String sanitize(final @Nullable String document) {
            if (document == null) {
                return null;
            }
            final int start = document.indexOf("```");
            if (start < 0) {
                return document;
            }
            final int contentStart = document.indexOf('\n', start) + 1;
            final int end = document.indexOf("```", contentStart);
            return document.substring(contentStart, end < 0 ? document.length() : end);
        }

        @Override
        public @Nullable String sanitize(final @Nullable String document, final boolean unsanitize) {
            return sanitize(document);
        }
    }
}
