package secondbrain.domain.tools.slack;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.jboss.weld.junit5.auto.ExcludeBeanClasses;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import secondbrain.domain.args.ArgsAccessorSimple;
import secondbrain.domain.concurrency.SharedVirtualThreadExecutor;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.exceptionhandling.LoggingExceptionHandler;
import secondbrain.domain.exceptions.ExternalFailure;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.objects.ToStringGenerator;
import secondbrain.domain.reader.FileReader;
import secondbrain.domain.test.TestArgs;
import secondbrain.domain.test.TestConfigUtil;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.tools.CommonArguments;
import secondbrain.domain.validate.ValidateStringBlank;
import secondbrain.domain.yaml.YamlDeserializerJackson;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(MultiSlackSearchCacheWarmer.class)
@AddBeanClasses(MultiSlackSearchCacheWarmerConfig.class)
@AddBeanClasses(ArgsAccessorSimple.class)
@AddBeanClasses(ValidateStringBlank.class)
@AddBeanClasses(YamlDeserializerJackson.class)
@AddBeanClasses(SharedVirtualThreadExecutor.class)
@AddBeanClasses(LoggingExceptionHandler.class)
@AddBeanClasses(Loggers.class)
// The Slack search tool is replaced by a mock from a producer method
@ExcludeBeanClasses(SlackSearch.class)
class MultiSlackSearchCacheWarmerTest {
    private static final String PROMPT = "Warm the cache";
    private static final String URL = "https://example.org/directory.yaml";
    private static final String DIRECTORY = """
            entities:
              - name: Acme
                salesforce: [sf1, " "]
                planhat: [ph1]
                zendesk: [z1]
                slack: [s1]
              - name: Globex
                salesforce: [sf2]
                disabled: true
              - name: Initech
                planhat: [ph3]
            """;

    /**
     * A call made to the Slack search tool.
     */
    record SearchCall(Map<String, String> context, List<String> prompts, List<ToolArgs> arguments) {
        String keyword() {
            return arguments.getFirst().argValue();
        }
    }

    private final Map<String, String> files = new HashMap<>(Map.of(URL, DIRECTORY));
    private final List<SearchCall> searches = new CopyOnWriteArrayList<>();
    private final Map<String, Supplier<RuntimeException>> failures = new ConcurrentHashMap<>();
    private final SlackSearch slackSearch = mock(SlackSearch.class);

    @Inject
    private MultiSlackSearchCacheWarmer tool;

    @Produces
    SlackSearch produceSlackSearch() {
        return slackSearch;
    }

    @Produces
    FileReader produceFileReader() {
        return path -> Objects.requireNonNull(files.get(path), () -> "No file " + path);
    }

    @Produces
    ToStringGenerator produceToStringGenerator() {
        return TestArgs.toStringGenerator("multi-slack-search-cache-warmer", 59);
    }

    @BeforeEach
    void setUp() {
        TestConfigUtil.registerConfig(Map.of());
        when(slackSearch.getContext(any(), any(), any())).thenAnswer(invocation -> {
            final Map<String, String> context = invocation.getArgument(0);
            final List<String> prompts = invocation.getArgument(1);
            final List<ToolArgs> arguments = invocation.getArgument(2);
            final SearchCall call = new SearchCall(context, prompts, arguments);
            searches.add(call);
            final Supplier<RuntimeException> failure = failures.get(call.keyword());
            if (failure != null) {
                throw failure.get();
            }
            return List.of(new RagDocumentContext<Void>("SlackSearch", "Slack", "messages for " + call.keyword(), List.of(), call.keyword()));
        });
    }

    private static List<ToolArgs> args(final String... nameValues) {
        final List<ToolArgs> arguments = new java.util.ArrayList<>();
        arguments.add(new ToolArgs(MultiSlackSearchCacheWarmer.MULTI_SLACK_SEARCH_CACHE_WARMER_URL_ARG, URL, true));
        for (int i = 0; i < nameValues.length; i += 2) {
            arguments.add(new ToolArgs(nameValues[i], nameValues[i + 1], true));
        }
        return arguments;
    }

    private List<String> searchedKeywords() {
        return searches.stream().map(SearchCall::keyword).sorted().toList();
    }

    private static List<String> ids(final List<RagDocumentContext<Void>> context) {
        return context.stream().map(RagDocumentContext::id).sorted().toList();
    }

    @Test
    void describesItself() {
        assertEquals("MultiSlackSearchCacheWarmer", tool.getName());
        assertEquals("Unused", tool.getContextLabel());
        assertEquals("Warms the slack cache by searching for keywords in slack messages.\n", tool.getDescription());
        assertEquals(List.of(MultiSlackSearchCacheWarmer.MULTI_SLACK_SEARCH_CACHE_WARMER_URL_ARG, MultiSlackSearchCacheWarmer.MULTI_SLACK_SEARCH_CACHE_WARMER_MAX_ENTITIES_ARG),
                tool.getArguments().stream().map(ToolArguments::name).toList());
    }

    @Test
    void getContextSearchesSlackForEachSalesforceAndPlanHatIdOfEnabledEntities() {
        final List<RagDocumentContext<Void>> context = tool.getContext(Map.of("key", "value"), List.of(PROMPT), args());

        // Disabled entities, blank IDs, and the ZenDesk and Slack IDs are not searched
        assertEquals(List.of("ph1", "ph3", "sf1"), searchedKeywords());
        assertEquals(List.of("ph1", "ph3", "sf1"), ids(context));
    }

    @Test
    void eachIdIsSearchedOnItsOwnWithTheEntityNameInTheContext() {
        tool.getContext(Map.of("key", "value"), List.of(PROMPT), args());

        final SearchCall acme = searches.stream().filter(c -> c.keyword().equals("sf1")).findFirst().orElseThrow();
        assertEquals(Map.of("key", "value", CommonArguments.ENTITY_NAME_CONTEXT_ARG, "Acme"), acme.context());
        assertEquals(List.of(PROMPT), acme.prompts());
        assertEquals(List.of(new ToolArgs(SlackSearch.SLACK_SEARCH_KEYWORDS_ARG, "sf1", true)), acme.arguments());
        final SearchCall initech = searches.stream().filter(c -> c.keyword().equals("ph3")).findFirst().orElseThrow();
        assertEquals("Initech", initech.context().get(CommonArguments.ENTITY_NAME_CONTEXT_ARG));
    }

    @Test
    void idsOfAnEntityAreSearchedInSortedOrder() {
        files.put(URL, """
                entities:
                  - name: Acme
                    salesforce: [b-sf, d-sf]
                    planhat: [a-ph, c-ph]
                """);

        tool.getContext(Map.of(), List.of(PROMPT), args());

        // The IDs of one entity are searched sequentially, merged by CollectionUtils.collate
        assertEquals(List.of("a-ph", "b-sf", "c-ph", "d-sf"), searches.stream().map(SearchCall::keyword).toList());
    }

    @Test
    void idsSharedBySalesforceAndPlanHatAreSearchedTwice() {
        // Looks like a bug: collate keeps duplicates, so an ID listed for both Salesforce and PlanHat is searched twice
        files.put(URL, """
                entities:
                  - name: Acme
                    salesforce: [shared]
                    planhat: [shared]
                """);

        final List<RagDocumentContext<Void>> context = tool.getContext(Map.of(), List.of(PROMPT), args());

        assertEquals(List.of("shared", "shared"), searchedKeywords());
        assertEquals(List.of("shared", "shared"), ids(context));
    }

    @Test
    void maxEntitiesLimitsTheEntitiesInDirectoryOrder() {
        files.put(URL, """
                entities:
                  - name: First
                    planhat: [p1]
                  - name: Second
                    planhat: [p2]
                  - name: Third
                    planhat: [p3]
                """);

        tool.getContext(Map.of(), List.of(PROMPT), args(MultiSlackSearchCacheWarmer.MULTI_SLACK_SEARCH_CACHE_WARMER_MAX_ENTITIES_ARG, "2"));

        assertEquals(List.of("p1", "p2"), searchedKeywords());
    }

    @Test
    void disabledEntitiesCountTowardsTheMaximum() {
        tool.getContext(Map.of(), List.of(PROMPT), args(MultiSlackSearchCacheWarmer.MULTI_SLACK_SEARCH_CACHE_WARMER_MAX_ENTITIES_ARG, "2"));

        // Acme and the disabled Globex are the first two entities, so Initech is not searched
        assertEquals(List.of("ph1", "sf1"), searchedKeywords());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "many"})
    void maxEntitiesOfZeroOrLessOrInvalidProcessesEveryEntity(final String maxEntities) {
        tool.getContext(Map.of(), List.of(PROMPT), args(MultiSlackSearchCacheWarmer.MULTI_SLACK_SEARCH_CACHE_WARMER_MAX_ENTITIES_ARG, maxEntities));

        assertEquals(List.of("ph1", "ph3", "sf1"), searchedKeywords());
    }

    @Test
    void maxEntitiesIsReadFromTheContextBeforeTheArgument() {
        tool.getContext(Map.of("multislacksearchcachewarmer_max_entities", "1"), List.of(PROMPT),
                args(MultiSlackSearchCacheWarmer.MULTI_SLACK_SEARCH_CACHE_WARMER_MAX_ENTITIES_ARG, "0"));

        assertEquals(List.of("ph1", "sf1"), searchedKeywords());
    }

    @Test
    void configuredMaxEntitiesWins() {
        TestConfigUtil.registerConfig(Map.of("sb.multislacksearchcachewarmer.maxentities", "1"));

        tool.getContext(Map.of("multislacksearchcachewarmer_max_entities", "0"), List.of(PROMPT),
                args(MultiSlackSearchCacheWarmer.MULTI_SLACK_SEARCH_CACHE_WARMER_MAX_ENTITIES_ARG, "0"));

        assertEquals(List.of("ph1", "sf1"), searchedKeywords());
    }

    @Test
    void urlIsReadFromTheContextBeforeTheArgument() {
        files.put("https://example.org/context.yaml", """
                entities:
                  - name: Context
                    planhat: [from-context]
                """);

        tool.getContext(Map.of("multislacksearchcachewarmer_url", "https://example.org/context.yaml"), List.of(PROMPT), args());

        assertEquals(List.of("from-context"), searchedKeywords());
    }

    @Test
    void configuredUrlWins() {
        files.put("https://example.org/config.yaml", """
                entities:
                  - name: Config
                    planhat: [from-config]
                """);
        TestConfigUtil.registerConfig(Map.of("sb.multislacksearchcachewarmer.url", "https://example.org/config.yaml"));

        tool.getContext(Map.of("multislacksearchcachewarmer_url", "https://example.org/context.yaml"), List.of(PROMPT), args());

        assertEquals(List.of("from-config"), searchedKeywords());
    }

    @Test
    void searchFailuresAreIgnored() {
        failures.put("ph1", () -> new InternalFailure("internal"));
        failures.put("sf1", () -> new ExternalFailure("external"));
        failures.put("ph3", () -> new IllegalStateException("unexpected"));
        files.put(URL, DIRECTORY + """
                  - name: Hooli
                    planhat: [ph4]
                """);

        final List<RagDocumentContext<Void>> context = tool.getContext(Map.of(), List.of(PROMPT), args());

        assertEquals(List.of("ph1", "ph3", "ph4", "sf1"), searchedKeywords());
        assertEquals(List.of("ph4"), ids(context));
    }

    @Test
    void getContextReturnsNothingWhenTheDirectoryCannotBeRead() {
        assertEquals(List.of(), tool.getContext(Map.of(), List.of(PROMPT), List.of(
                new ToolArgs(MultiSlackSearchCacheWarmer.MULTI_SLACK_SEARCH_CACHE_WARMER_URL_ARG, "https://example.org/missing.yaml", true))));
        assertEquals(List.of(), tool.getContext(Map.of(), List.of(PROMPT), List.of()));
        assertTrue(searches.isEmpty());
    }

    @Test
    void getContextReturnsNothingWhenTheDirectoryIsInvalid() {
        files.put(URL, "entities: [unclosed");

        assertEquals(List.of(), tool.getContext(Map.of(), List.of(PROMPT), args()));
        assertTrue(searches.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"entities: []", "{}"})
    void getContextReturnsNothingForADirectoryWithoutEntities(final String directory) {
        files.put(URL, directory);

        assertEquals(List.of(), tool.getContext(Map.of(), List.of(PROMPT), args()));
        assertTrue(searches.isEmpty());
    }

    @Test
    void getContextReturnsNothingForADirectoryWithUnknownFields() {
        // Looks like a bug: entities ignore unknown fields, but the directory itself does not, so one extra top level
        // field stops the whole cache warm
        files.put(URL, DIRECTORY + "version: 2\n");

        assertEquals(List.of(), tool.getContext(Map.of(), List.of(PROMPT), args()));
        assertTrue(searches.isEmpty());
    }

    @Test
    void getContextUsesAnEmptyPromptWhenThereAreNoPrompts() {
        tool.getContext(Map.of(), List.of(), args());

        assertEquals(List.of(List.of("")), searches.stream().map(SearchCall::prompts).distinct().toList());
    }

    @Test
    void callReportsThatTheCacheWasWarmedForEachPrompt() {
        final RagMultiDocumentContext<Void> result = tool.call(Map.of(), List.of(PROMPT, "second prompt"), args());

        assertEquals(List.of("Cache has been warmed", "Cache has been warmed"), result.getResponses());
        assertEquals(List.of("ph1", "ph3", "sf1"), ids(result.getIndividualContexts()));
        assertEquals(List.of(PROMPT, "second prompt"), result.getPrompts());
        // Only the first prompt is passed to the searches
        assertEquals(List.of(List.of(PROMPT)), searches.stream().map(SearchCall::prompts).distinct().toList());
    }

    @Test
    void callStillReportsSuccessWhenTheDirectoryCannotBeRead() {
        final RagMultiDocumentContext<Void> result = tool.call(Map.of(), List.of(PROMPT), List.of());

        assertEquals(List.of("Cache has been warmed"), result.getResponses());
        assertEquals(List.of(), result.getIndividualContexts());
    }

    @Test
    void contextHashCodeCombinesArgumentsAndPrompts() {
        assertEquals(31 * 59 + List.of(PROMPT).hashCode(), tool.contextHashCode(Map.of(), List.of(PROMPT), args()));
    }
}
