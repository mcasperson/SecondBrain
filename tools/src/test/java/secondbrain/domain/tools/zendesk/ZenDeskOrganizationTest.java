package secondbrain.domain.tools.zendesk;

import io.smallrye.common.annotation.Identifier;
import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.jboss.weld.junit5.auto.ExcludeBeanClasses;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import secondbrain.domain.args.ArgsAccessorSimple;
import secondbrain.domain.config.LocalConfigFilteredParent;
import secondbrain.domain.config.LocalConfigSummarizer;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.date.DateParser;
import secondbrain.domain.debug.DebugToolArgsKeyValue;
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
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.processing.ContextLabelCallback;
import secondbrain.domain.processing.RagDocSummarizer;
import secondbrain.domain.processing.RatingFilter;
import secondbrain.domain.sanitize.SanitizeArgument;
import secondbrain.domain.sanitize.SanitizeDocument;
import secondbrain.domain.test.RecordingLlmClient;
import secondbrain.domain.test.RecordingLocalStorage;
import secondbrain.domain.test.TestArgs;
import secondbrain.domain.test.TestConfigUtil;
import secondbrain.domain.test.TestHooks;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.tools.CommonArguments;
import secondbrain.domain.tools.keyword.Keywords;
import secondbrain.domain.validate.ValidateInputs;
import secondbrain.domain.validate.ValidateListEmptyOrNull;
import secondbrain.domain.validate.ValidateStringBlank;
import secondbrain.infrastructure.llm.LlmClient;
import secondbrain.infrastructure.zendesk.ZenDeskClient;
import secondbrain.infrastructure.zendesk.api.ZenDeskCommentsResponse;
import secondbrain.infrastructure.zendesk.api.ZenDeskOrganizationItemResponse;
import secondbrain.infrastructure.zendesk.api.ZenDeskTicket;
import secondbrain.infrastructure.zendesk.api.ZenDeskUserItemResponse;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ZenDeskOrganization}. The ZenDesk API is replaced by an in-memory client, and the individual
 * ticket tool is a mock that turns each ticket into a document, so no network or LLM access is needed.
 */
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(ZenDeskOrganization.class)
@AddBeanClasses(ZenDeskConfig.class)
@AddBeanClasses(ArgsAccessorSimple.class)
@AddBeanClasses(ValidateStringBlank.class)
@AddBeanClasses(ValidateListEmptyOrNull.class)
@AddBeanClasses(DocumentTrimmerExactKeywords.class)
@AddBeanClasses(DebugToolArgsKeyValue.class)
@AddBeanClasses(StandardExceptionMapping.class)
@AddBeanClasses(NamedHooksContainer.class)
@AddBeanClasses(Loggers.class)
// The individual ticket tool and keywords tool are replaced by mocks from producer methods
@ExcludeBeanClasses({ZenDeskIndividualTicket.class, Keywords.class})
class ZenDeskOrganizationTest {
    private static final String PROMPT = "What are Acme's support issues?";
    private static final String TOOL = "ZenDeskOrganization";
    private static final String URL = "https://acme.zendesk.com";
    private static final String URL2 = "https://second.zendesk.com";
    private static final String AUTH = auth("agent@example.com", "secret");
    private static final String AUTH2 = auth("agent2@example.com", "secret2");
    private static final int DEFAULT_SEARCH_TTL = 60 * 60 * 24 * 90;
    private static final int DEFAULT_CACHE_TTL = 60 * 60 * 24;

    private final FakeZenDeskClient zenDeskClient = new FakeZenDeskClient();
    private final RecordingLlmClient llmClient = new RecordingLlmClient();
    private final RecordingLocalStorage localStorage = new RecordingLocalStorage();
    private final RejectingRatingFilter ratingFilter = new RejectingRatingFilter();
    private final PrefixSummarizer summarizer = new PrefixSummarizer();
    private final ZenDeskIndividualTicket ticketTool = mock(ZenDeskIndividualTicket.class);
    private final List<Map<String, String>> ticketToolArguments = new CopyOnWriteArrayList<>();
    private final List<List<String>> ticketToolPrompts = new CopyOnWriteArrayList<>();

    @Inject
    private ZenDeskOrganization tool;

    @Produces
    @Preferred
    ZenDeskClient produceZenDeskClient() {
        return zenDeskClient;
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
    RatingFilter produceRatingFilter() {
        return ratingFilter;
    }

    @Produces
    @Preferred
    RagDocSummarizer produceRagDocSummarizer() {
        return summarizer;
    }

    @Produces
    ZenDeskIndividualTicket produceTicketTool() {
        return ticketTool;
    }

    @Produces
    Keywords produceKeywords() {
        return mock(Keywords.class);
    }

    @Produces
    ValidateInputs produceValidateInputs() {
        return new ValidateInputs() {
            @Override
            public String getCommaSeparatedList(final String input) {
                return input;
            }

            @Override
            public String getCommaSeparatedList(final String prompt, final String input) {
                return input;
            }
        };
    }

    @Produces
    @Identifier("AES")
    Encryptor produceEncryptor() {
        return TestArgs.encryptor();
    }

    @Produces
    @Identifier("everything")
    DateParser produceDateParser() {
        return ZonedDateTime::parse;
    }

    @Produces
    @Identifier("sanitizeEmail")
    SanitizeArgument produceSanitizeEmail() {
        return (argument, document) -> argument == null ? "" : argument.trim();
    }

    @Produces
    @Identifier("sanitizeOrganization")
    SanitizeArgument produceSanitizeOrganization() {
        return (argument, document) -> argument == null ? "" : argument.trim();
    }

    @Produces
    @Identifier("removeSpacing")
    SanitizeDocument produceRemoveSpacing() {
        return new SanitizeDocument() {
            @Override
            public @Nullable String sanitize(@Nullable final String document) {
                return document;
            }

            @Override
            public @Nullable String sanitize(@Nullable final String document, final boolean unsanitize) {
                return document;
            }
        };
    }

    @Produces
    ToStringGenerator produceToStringGenerator() {
        return TestArgs.toStringGenerator("zendesk-organization", 29);
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
        // Each ticket becomes a document describing the ticket ID and subject
        when(ticketTool.getContext(anyMap(), anyList(), anyList())).thenAnswer(invocation -> {
            final List<ToolArgs> arguments = invocation.getArgument(2);
            final Map<String, String> values = arguments.stream().collect(Collectors.toMap(ToolArgs::argName, ToolArgs::argValue));
            ticketToolArguments.add(values);
            ticketToolPrompts.add(invocation.getArgument(1));
            final String id = values.get(ZenDeskIndividualTicket.ZENDESK_TICKET_ID_ARG);
            return List.of(new RagDocumentContext<Void>("ZenDeskIndividualTicket", "ZenDesk Ticket",
                    "Ticket " + id + " about " + values.get(ZenDeskIndividualTicket.ZENDESK_TICKET_SUBJECT_ARG), List.of(), id));
        });
    }

    private static String auth(final String user, final String token) {
        return "Basic " + Base64.getEncoder().encodeToString((user + "/token:" + token).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Credentials as an encrypted URL and user and a plain text token.
     */
    private static Map<String, String> env() {
        return Map.of(
                "zendesk_url", "enc(" + URL + ")",
                "zendesk_user", "enc(agent@example.com)",
                "zendesk_access_token", "secret");
    }

    private static Map<String, String> envWithSecondInstance() {
        final Map<String, String> env = new HashMap<>(env());
        env.put("zendesk_access_token2", "secret2");
        return env;
    }

    private static List<ToolArgs> args(final String... nameValues) {
        final List<ToolArgs> arguments = new ArrayList<>();
        for (int i = 0; i < nameValues.length; i += 2) {
            arguments.add(new ToolArgs(nameValues[i], nameValues[i + 1], true));
        }
        return arguments;
    }

    private static List<ToolArgs> secondInstanceArgs(final String... nameValues) {
        final List<ToolArgs> arguments = args(nameValues);
        arguments.add(new ToolArgs(ZenDeskOrganization.ZENDESK_URL2_ARG, URL2, true));
        arguments.add(new ToolArgs(ZenDeskOrganization.ZENDESK_USER2_ARG, "agent2@example.com", true));
        return arguments;
    }

    /**
     * A ticket that passes the default filters: it has an assignee and an organization.
     */
    private static ZenDeskTicket ticket(final String id, @Nullable final String organization) {
        return ticket(id, "submitter-" + id, "assignee-" + id, organization, "support@acme.com");
    }

    private static ZenDeskTicket ticket(final String id, @Nullable final String submitter, @Nullable final String assignee,
                                        @Nullable final String organization, @Nullable final String recipient) {
        return new ZenDeskTicket(id, submitter, assignee, "Subject " + id, organization, recipient, null, null, "open", "2024-03-01T10:00:00Z");
    }

    private static List<String> ids(final List<RagDocumentContext<Void>> context) {
        return context.stream().map(RagDocumentContext::id).toList();
    }

    private static List<String> documents(final List<RagDocumentContext<Void>> context) {
        return context.stream().map(RagDocumentContext::document).toList();
    }

    private static String startOfYesterday() {
        return OffsetDateTime.now(ZoneId.systemDefault()).truncatedTo(ChronoUnit.DAYS).minusDays(1).format(ISO_OFFSET_DATE_TIME);
    }

    private static String startOfTomorrow() {
        return OffsetDateTime.now(ZoneId.systemDefault()).truncatedTo(ChronoUnit.DAYS).plusDays(1).format(ISO_OFFSET_DATE_TIME);
    }

    @Test
    void describesItself() {
        assertEquals(TOOL, tool.getName());
        assertEquals("ZenDesk Ticket", tool.getContextLabel());
        assertEquals("Returns the details of ZenDesk support tickets", tool.getDescription());
        assertEquals(List.of(ZenDeskOrganization.ZENDESK_ORGANIZATION_ARG, ZenDeskOrganization.EXCLUDE_ORGANIZATION_ARG,
                        CommonArguments.KEYWORDS_ARG, CommonArguments.KEYWORD_WINDOW_ARG, CommonArguments.AUTO_GENERATE_KEYWORDS_ARG,
                        ZenDeskOrganization.EXCLUDE_SUBMITTERS_ARG, ZenDeskOrganization.RECIPIENT_ARG, ZenDeskOrganization.NUM_COMMENTS_ARG,
                        CommonArguments.DAYS_ARG, CommonArguments.HOURS_ARG, CommonArguments.REQUIRE_COMPANY),
                tool.getArguments().stream().map(ToolArguments::name).toList());
    }

    @Test
    void getContextReturnsASummaryOfEachTicket() {
        zenDeskClient.add(URL, ticket("1", "org-1"), ticket("2", "org-2"));

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args());

        assertEquals(List.of("1", "2"), ids(context));
        // Tickets are summarised by default
        assertEquals(List.of("Summary: Ticket 1 about Subject 1", "Summary: Ticket 2 about Subject 2"), documents(context));
        assertEquals(List.of(TOOL), summarizer.tools);
        assertTrue(llmClient.requests.isEmpty());
    }

    @Test
    void getContextPassesTheTicketDetailsToTheTicketTool() {
        zenDeskClient.add(URL, ticket("1", "org-1"));

        tool.getContext(env(), List.of(PROMPT), args(
                CommonArguments.KEYWORDS_ARG, "billing,refund",
                CommonArguments.KEYWORD_WINDOW_ARG, "500",
                CommonArguments.CONTENT_RATING_QUESTION_ARG, "Is this about billing?",
                CommonArguments.DEFAULT_RATING_ARG, "4"));

        final Map<String, String> arguments = ticketToolArguments.getFirst();
        assertEquals("1", arguments.get(ZenDeskIndividualTicket.ZENDESK_TICKET_ID_ARG));
        assertEquals("Subject 1", arguments.get(ZenDeskIndividualTicket.ZENDESK_TICKET_SUBJECT_ARG));
        assertEquals("org-1", arguments.get(ZenDeskIndividualTicket.ZENDESK_TICKET_ORGANIZATION_ARG));
        assertEquals("2024-03-01T10:00:00Z", arguments.get(ZenDeskIndividualTicket.ZENDESK_TICKET_CREATED_AT_ARG));
        assertEquals("submitter-1", arguments.get(ZenDeskIndividualTicket.ZENDESK_TICKET_SUBMITTER_ARG));
        assertEquals("assignee-1", arguments.get(ZenDeskIndividualTicket.ZENDESK_TICKET_ASSIGNEE_ARG));
        assertEquals(URL, arguments.get(ZenDeskIndividualTicket.ZENDESK_URL_ARG));
        assertEquals("agent@example.com", arguments.get(ZenDeskIndividualTicket.ZENDESK_EMAIL_ARG));
        assertEquals("secret", arguments.get(ZenDeskIndividualTicket.ZENDESK_TOKEN_ARG));
        assertEquals("Is this about billing?", arguments.get(ZenDeskIndividualTicket.ZENDESK_RATING_QUESTION_ARG));
        assertEquals("4", arguments.get(ZenDeskIndividualTicket.ZENDESK_DEFAULT_RATING_ARG));
        assertEquals("billing,refund", arguments.get(CommonArguments.KEYWORDS_ARG));
        assertEquals("500", arguments.get(CommonArguments.KEYWORD_WINDOW_ARG));
        // The ticket tool is prompted to summarise the ticket rather than answer the original prompt
        assertTrue(ticketToolPrompts.getFirst().getFirst().startsWith("Summarise the ticket in one paragraph."));
    }

    @Test
    void getContextSendsTheCredentialsAndTtlToTheClient() {
        tool.getContext(env(), List.of(PROMPT), args(ZenDeskOrganization.ZENDESK_HISTORY_TTL_ARG, "600"));

        final FakeZenDeskClient.Call call = zenDeskClient.calls.getFirst();
        assertEquals(AUTH, call.authorization());
        assertEquals(URL, call.url());
        assertEquals(600, call.ttlSeconds());
    }

    @Test
    void getContextUsesTheDefaultSearchTtl() {
        tool.getContext(env(), List.of(PROMPT), args());

        assertEquals(DEFAULT_SEARCH_TTL, zenDeskClient.calls.getFirst().ttlSeconds());
    }

    @Test
    void getContextDefaultsToTicketsSinceTheStartOfYesterday() {
        final String before = "type:ticket created>" + startOfYesterday() + " created<" + startOfTomorrow();

        tool.getContext(env(), List.of(PROMPT), args(ZenDeskOrganization.ZENDESK_ORGANIZATION_ARG, "org-1"));

        // The organization is not added to the query unless filterByOrganization is set
        final String after = "type:ticket created>" + startOfYesterday() + " created<" + startOfTomorrow();
        final String query = zenDeskClient.calls.getFirst().query();
        assertTrue(query.equals(before) || query.equals(after), () -> "unexpected query " + query);
    }

    @Test
    void getContextQueriesTheSuppliedPeriodAndOrganization() {
        TestConfigUtil.registerConfig(Map.of("sb.zendesk.filterbyorganization", "true"));

        tool.getContext(env(), List.of(PROMPT), args(
                ZenDeskOrganization.ZENDESK_ORGANIZATION_ARG, "org-1",
                CommonArguments.START_DATE, "2024-03-01T00:00:00Z",
                CommonArguments.END_DATE, "2024-03-08T00:00:00Z"));

        assertEquals("type:ticket created>2024-03-01T00:00:00Z created<2024-03-08T00:00:00Z organization:org-1",
                zenDeskClient.calls.getFirst().query());
    }

    @Test
    void getContextFiltersTheQueryByOrganizationWhenTheArgumentIsSet() {
        tool.getContext(env(), List.of(PROMPT), args(
                ZenDeskOrganization.ZENDESK_ORGANIZATION_ARG, "org-1",
                ZenDeskOrganization.ZENDESK_CONTEXT_FILTER_BY_ORGANIZATION_ARG, "true",
                CommonArguments.START_DATE, "2024-03-01T00:00:00Z"));

        assertEquals("type:ticket created>2024-03-01T00:00:00Z organization:org-1", zenDeskClient.calls.getFirst().query());
    }

    @Test
    void getContextDoesNotFilterTheQueryByOrganizationByDefault() {
        tool.getContext(env(), List.of(PROMPT), args(
                ZenDeskOrganization.ZENDESK_ORGANIZATION_ARG, "org-1",
                CommonArguments.START_DATE, "2024-03-01T00:00:00Z"));

        assertEquals("type:ticket created>2024-03-01T00:00:00Z", zenDeskClient.calls.getFirst().query());
    }

    @Test
    void getContextPrefersTheFilterByOrganizationConfigOverTheArgument() {
        TestConfigUtil.registerConfig(Map.of("sb.zendesk.filterbyorganization", "false"));

        tool.getContext(env(), List.of(PROMPT), args(
                ZenDeskOrganization.ZENDESK_ORGANIZATION_ARG, "org-1",
                ZenDeskOrganization.ZENDESK_CONTEXT_FILTER_BY_ORGANIZATION_ARG, "true",
                CommonArguments.START_DATE, "2024-03-01T00:00:00Z"));

        assertEquals("type:ticket created>2024-03-01T00:00:00Z", zenDeskClient.calls.getFirst().query());
    }

    @Test
    void getContextQueriesFromTheStartDateOnly() {
        tool.getContext(env(), List.of(PROMPT), args(CommonArguments.START_DATE, "2024-03-01T00:00:00Z", CommonArguments.DAYS_ARG, "3"));

        assertEquals("type:ticket created>2024-03-01T00:00:00Z", zenDeskClient.calls.getFirst().query());
    }

    @Test
    void getContextQueriesUpToTheEndDateOnly() {
        tool.getContext(env(), List.of(PROMPT), args(CommonArguments.END_DATE, "2024-03-08T00:00:00Z"));

        assertEquals("type:ticket created<2024-03-08T00:00:00Z", zenDeskClient.calls.getFirst().query());
    }

    @Test
    void getContextDoesNotFilterTheQueryByABlankOrganization() {
        TestConfigUtil.registerConfig(Map.of("sb.zendesk.filterbyorganization", "true"));

        tool.getContext(env(), List.of(PROMPT), args(CommonArguments.START_DATE, "2024-03-01T00:00:00Z"));

        assertEquals("type:ticket created>2024-03-01T00:00:00Z", zenDeskClient.calls.getFirst().query());
    }

    @Test
    void getContextFiltersTicketsBySubmitterOrganizationAndAssignee() {
        zenDeskClient.add(URL,
                ticket("1", "org-1"),
                ticket("2", "submitter-2", null, "org-1", null),
                ticket("3", "bot", "assignee-3", "org-1", null),
                ticket("4", "spam", "assignee-4", "org-1", null),
                ticket("5", "org-x"),
                ticket("6", "org-2"));

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args(
                ZenDeskOrganization.EXCLUDE_SUBMITTERS_ARG, "bot, spam",
                ZenDeskOrganization.EXCLUDE_ORGANIZATION_ARG, "org-x"));

        // 2 has no assignee, 3 and 4 have excluded submitters, and 5 has an excluded organization
        assertEquals(List.of("1", "6"), ids(context));
    }

    @Test
    void getContextKeepsTicketsWithoutAnOrganization() {
        zenDeskClient.add(URL, ticket("1", "org-1"), ticket("2", null), ticket("3", ""));

        assertEquals(List.of("1", "2", "3"), ids(tool.getContext(env(), List.of(PROMPT), args())));
    }

    @Test
    void getContextIgnoresBlankEntriesInTheConfiguredExcludedOrganizations() {
        TestConfigUtil.registerConfig(Map.of("sb.zendesk.excludedorgs", " org-x , ,"));
        zenDeskClient.add(URL, ticket("1", "org-1"), ticket("2", "org-x"), ticket("3", ""));

        assertEquals(List.of("1", "3"), ids(tool.getContext(env(), List.of(PROMPT), args())));
    }

    @Test
    void getContextKeepsOnlyTicketsForTheOrganization() {
        zenDeskClient.add(URL, ticket("1", "org-1"), ticket("2", "org-2"), ticket("3", "org-1"));

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args(
                ZenDeskOrganization.ZENDESK_ORGANIZATION_ARG, "org-1"));

        assertEquals(List.of("1", "3"), ids(context));
    }

    @Test
    void getContextKeepsOnlyTicketsSentToTheRecipient() {
        zenDeskClient.add(URL,
                ticket("1", "s1", "a1", "org-1", "sales@acme.com"),
                ticket("2", "s2", "a2", "org-1", "support@acme.com"),
                ticket("3", "s3", "a3", "org-1", null));

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args(
                ZenDeskOrganization.RECIPIENT_ARG, "support@acme.com"));

        assertEquals(List.of("2"), ids(context));
    }

    @Test
    void getContextTreatsAnEmailOrganizationAsTheRecipient() {
        TestConfigUtil.registerConfig(Map.of("sb.zendesk.filterbyorganization", "true"));
        zenDeskClient.add(URL,
                ticket("1", "s1", "a1", "support@acme.com", "sales@acme.com"),
                ticket("2", "s2", "a2", "org-2", "support@acme.com"),
                ticket("3", "s3", "a3", "support@acme.com", "support@acme.com"));

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args(
                ZenDeskOrganization.ZENDESK_ORGANIZATION_ARG, "support@acme.com",
                CommonArguments.START_DATE, "2024-03-01T00:00:00Z"));

        // The email address is used as the recipient, and is not used to filter the organization
        assertEquals(List.of("2", "3"), ids(context));
        assertEquals("type:ticket created>2024-03-01T00:00:00Z", zenDeskClient.calls.getFirst().query());
    }

    @Test
    void getContextKeepsAnEmailOrganizationWhenARecipientIsSupplied() {
        zenDeskClient.add(URL,
                ticket("1", "s1", "a1", "support@acme.com", "sales@acme.com"),
                ticket("2", "s2", "a2", "org-2", "sales@acme.com"),
                ticket("3", "s3", "a3", "support@acme.com", "support@acme.com"));

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args(
                ZenDeskOrganization.ZENDESK_ORGANIZATION_ARG, "support@acme.com",
                ZenDeskOrganization.RECIPIENT_ARG, "sales@acme.com"));

        assertEquals(List.of("1"), ids(context));
    }

    @Test
    void getContextLimitsTheNumberOfTickets() {
        zenDeskClient.add(URL, ticket("1", "org-1"), ticket("2", "org-1"), ticket("3", "org-1"));

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args(
                ZenDeskOrganization.ZENDESK_MAX_TICKETS_ARG, "2"));

        assertEquals(List.of("1", "2"), ids(context));
        assertEquals(2, ticketToolArguments.size());
    }

    @Test
    void getContextNeverProcessesMoreThanOneHundredTickets() {
        zenDeskClient.add(URL, IntStream.rangeClosed(1, 101).mapToObj(i -> ticket(Integer.toString(i), "org-1")).toArray(ZenDeskTicket[]::new));

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args(
                ZenDeskOrganization.ZENDESK_MAX_TICKETS_ARG, "500"));

        assertEquals(100, context.size());
        assertEquals("100", context.getLast().id());
    }

    @Test
    void getContextDropsTicketsThatDoNotMeetTheRating() {
        zenDeskClient.add(URL, ticket("1", "org-1"), ticket("2", "org-1"));
        ratingFilter.rejected.add("1");

        assertEquals(List.of("2"), ids(tool.getContext(env(), List.of(PROMPT), args())));
    }

    @Test
    void getContextDropsTicketsWithoutTheKeywords() {
        zenDeskClient.add(URL, new ZenDeskTicket("1", "s1", "a1", "billing problem", "org-1", null, null, null, null, null),
                new ZenDeskTicket("2", "s2", "a2", "login problem", "org-1", null, null, null, null, null));

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args(
                CommonArguments.KEYWORDS_ARG, "billing",
                CommonArguments.SUMMARIZE_DOCUMENT_ARG, "false"));

        assertEquals(List.of("Ticket 1 about billing problem"), documents(context));
    }

    @Test
    void getContextCanSkipTheSummary() {
        zenDeskClient.add(URL, ticket("1", "org-1"));

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args(
                CommonArguments.SUMMARIZE_DOCUMENT_ARG, "false"));

        assertEquals(List.of("Ticket 1 about Subject 1"), documents(context));
        assertTrue(summarizer.tools.isEmpty());
    }

    @Test
    void getContextCombinesTicketsFromBothZenDeskInstances() {
        zenDeskClient.add(URL, ticket("1", "org-1"));
        zenDeskClient.add(URL2, ticket("2", "org-1"));

        final List<RagDocumentContext<Void>> context = tool.getContext(envWithSecondInstance(), List.of(PROMPT), secondInstanceArgs());

        assertEquals(List.of("1", "2"), ids(context));
        assertEquals(List.of(AUTH, AUTH2), zenDeskClient.calls.stream().map(FakeZenDeskClient.Call::authorization).toList());
        assertEquals(List.of(URL, URL2), zenDeskClient.calls.stream().map(FakeZenDeskClient.Call::url).toList());
        assertEquals("secret2", ticketToolArguments.getLast().get(ZenDeskIndividualTicket.ZENDESK_TOKEN_ARG));
        assertEquals("agent2@example.com", ticketToolArguments.getLast().get(ZenDeskIndividualTicket.ZENDESK_EMAIL_ARG));
    }

    @Test
    void getContextKeepsTicketsFromOneInstanceWhenTheOtherFails() {
        zenDeskClient.failingUrls.add(URL);
        zenDeskClient.add(URL2, ticket("2", "org-1"));

        final List<RagDocumentContext<Void>> context = tool.getContext(envWithSecondInstance(), List.of(PROMPT), secondInstanceArgs());

        assertEquals(List.of("2"), ids(context));
        assertEquals(2, zenDeskClient.calls.size());
    }

    @Test
    void getContextIgnoresTheSecondInstanceWithoutAToken() {
        tool.getContext(env(), List.of(PROMPT), secondInstanceArgs());

        assertEquals(List.of(URL), zenDeskClient.calls.stream().map(FakeZenDeskClient.Call::url).toList());
    }

    @ParameterizedTest
    @CsvSource({"zendesk_url", "zendesk_user", "zendesk_access_token"})
    void getContextReturnsNothingWhenACredentialIsMissing(final String missing) {
        zenDeskClient.add(URL, ticket("1", "org-1"));
        final Map<String, String> env = new HashMap<>(env());
        env.remove(missing);

        assertEquals(List.of(), tool.getContext(env, List.of(PROMPT), args()));
        assertTrue(zenDeskClient.calls.isEmpty());
    }

    @Test
    void getContextUsesConfiguredCredentialsWhenTheContextHasNone() {
        TestConfigUtil.registerConfig(Map.of(
                "sb.zendesk.url", "https://config.zendesk.com",
                "sb.zendesk.user", "agent@example.com",
                "sb.zendesk.accesstoken", "secret"));

        tool.getContext(Map.of(), List.of(PROMPT), args());

        assertEquals(AUTH, zenDeskClient.calls.getFirst().authorization());
        assertEquals("https://config.zendesk.com", zenDeskClient.calls.getFirst().url());
    }

    @Test
    void getContextRequiresAnOrganizationWhenRequested() {
        zenDeskClient.add(URL, ticket("1", "org-1"));

        assertEquals(List.of(), tool.getContext(env(), List.of(PROMPT), args(CommonArguments.REQUIRE_COMPANY, "true")));
        assertTrue(zenDeskClient.calls.isEmpty());
        assertTrue(localStorage.calls.isEmpty());

        assertEquals(List.of("1"), ids(tool.getContext(env(), List.of(PROMPT), args(
                CommonArguments.REQUIRE_COMPANY, "true",
                ZenDeskOrganization.ZENDESK_ORGANIZATION_ARG, "org-1"))));
    }

    @Test
    void getContextSkipsOrganizationsWithoutRecentTickets() {
        zenDeskClient.add(URL, ticket("1", "org-1"));
        zenDeskClient.anyItems = false;

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args(
                CommonArguments.SKIP_EMPTY_IN_LAST_DURATION, "true",
                ZenDeskOrganization.ZENDESK_ORGANIZATION_ARG, "org-1"));

        assertEquals(List.of(), context);
        assertEquals(List.of(AUTH + "|" + URL + "|org-1|Years|Months"), zenDeskClient.anyItemsCalls);
        assertTrue(zenDeskClient.calls.isEmpty());
    }

    @Test
    void getContextContinuesForOrganizationsWithRecentTickets() {
        zenDeskClient.add(URL, ticket("1", "org-1"));

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args(
                CommonArguments.SKIP_EMPTY_IN_LAST_DURATION, "true",
                ZenDeskOrganization.ZENDESK_ORGANIZATION_ARG, "org-1"));

        assertEquals(List.of("1"), ids(context));
        assertEquals(1, zenDeskClient.anyItemsCalls.size());
    }

    @Test
    void getContextDoesNotCheckForRecentTicketsByDefault() {
        tool.getContext(env(), List.of(PROMPT), args());

        assertTrue(zenDeskClient.anyItemsCalls.isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"'', " + DEFAULT_CACHE_TTL, "60, 60", "-5, 0", "abc, " + DEFAULT_CACHE_TTL})
    void getContextCachesTheResultForTheTtl(final String ttl, final long expected) {
        tool.getContext(env(), List.of(PROMPT), ttl.isEmpty() ? args() : args(ZenDeskOrganization.TTL_SECONDS, ttl));

        final RecordingLocalStorage.Call call = localStorage.calls.getFirst();
        assertEquals(TOOL, call.tool());
        assertEquals(TOOL, call.source());
        assertEquals(expected, call.ttlSeconds());
    }

    @Test
    void getContextReturnsTheCachedResultWithoutCallingZenDesk() {
        final List<RagDocumentContext<Void>> cached = List.of(new RagDocumentContext<>(TOOL, "ZenDesk Ticket", "cached ticket", List.of(), "c1"));
        localStorage.prime(TOOL, cached);

        assertEquals(cached, tool.getContext(env(), List.of(PROMPT), args()));
        assertTrue(zenDeskClient.calls.isEmpty());
    }

    @Test
    void getContextReturnsNothingForANullCachedResult() {
        localStorage.primeNull(TOOL);

        assertEquals(List.of(), tool.getContext(env(), List.of(PROMPT), args()));
        assertTrue(zenDeskClient.calls.isEmpty());
    }

    @Test
    void getContextAppliesThePreinitializationAndPreprocessingHooks() {
        zenDeskClient.add(URL, ticket("1", "org-1"));

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args(
                CommonArguments.SUMMARIZE_DOCUMENT_ARG, "false",
                CommonArguments.PREINITIALIZATION_HOOKS_ARG, "append",
                CommonArguments.PREPROCESSOR_HOOKS_ARG, "upper"));

        // The preinitialization documents come first, and the preprocessing hooks see every document
        assertEquals(List.of("HOOK DOCUMENT", "TICKET 1 ABOUT SUBJECT 1"), documents(context));
        assertEquals(List.of("append", "1"), ids(context));
    }

    @Test
    void getContextHandlesAnEmptyPromptList() {
        zenDeskClient.add(URL, ticket("1", "org-1"));

        assertEquals(List.of("1"), ids(tool.getContext(env(), List.of(), args())));
    }

    @Test
    void callAnswersFromTheTickets() {
        zenDeskClient.add(URL, ticket("1", "org-1"));

        final RagMultiDocumentContext<Void> result = tool.call(env(), List.of(PROMPT), args(CommonArguments.POSTINFERENCE_HOOKS_ARG, "post"));

        assertEquals("answer [post]", result.getResponse());
        final RecordingLlmClient.Request request = llmClient.requests.getFirst();
        assertEquals(TOOL, request.tool());
        assertEquals(env(), request.environmentSettings());
        assertEquals(List.of(PROMPT), request.document().getPrompts());
        assertTrue(Objects.requireNonNull(request.document().instructions()).startsWith("You are an expert in reading help desk tickets."));
        assertEquals(List.of("Summary: Ticket 1 about Subject 1"),
                request.document().getIndividualContexts().stream().map(RagDocumentContext::document).toList());
    }

    @Test
    void callReportsThatNoTicketsWereFound() {
        final String before = startOfYesterday();
        final RagMultiDocumentContext<Void> result = tool.call(env(), List.of(PROMPT), args(ZenDeskOrganization.ZENDESK_ORGANIZATION_ARG, "org-1"));
        final String after = startOfYesterday();

        // The message reports the start of the default day range, which may roll over at midnight during the test
        assertTrue(List.of(
                        "No tickets found after " + before + " for organization 'org-1'",
                        "No tickets found after " + after + " for organization 'org-1'")
                .contains(result.getResponse()), result.getResponse());
        assertTrue(result.getIndividualContexts().isEmpty());
        assertTrue(llmClient.requests.isEmpty());
    }

    @Test
    void callMapsLlmFailuresToInternalFailure() {
        zenDeskClient.add(URL, ticket("1", "org-1"));
        llmClient.respondWith(doc -> {
            throw new IllegalStateException("LLM down");
        });

        final InternalFailure ex = assertThrows(InternalFailure.class, () -> tool.call(env(), List.of(PROMPT), args()));

        assertInstanceOf(IllegalStateException.class, ex.getCause());
    }

    @Test
    void contextHashCodeCombinesArgumentsAndPrompts() {
        assertEquals(31 * 29 + List.of(PROMPT).hashCode(), tool.contextHashCode(env(), List.of(PROMPT), args()));
    }

    /**
     * Returns the tickets added for each URL and records each call. Calls to a failing URL throw.
     */
    private static final class FakeZenDeskClient implements ZenDeskClient {
        record Call(String authorization, String url, String query, int ttlSeconds) {
        }

        final Map<String, List<ZenDeskTicket>> tickets = new HashMap<>();
        final Set<String> failingUrls = new HashSet<>();
        final List<Call> calls = new CopyOnWriteArrayList<>();
        final List<String> anyItemsCalls = new CopyOnWriteArrayList<>();
        boolean anyItems = true;

        void add(final String url, final ZenDeskTicket... newTickets) {
            tickets.computeIfAbsent(url, key -> new ArrayList<>()).addAll(List.of(newTickets));
        }

        @Override
        public List<ZenDeskTicket> getTickets(final String authorization, final String url, final String query, final int ttlSeconds) {
            calls.add(new Call(authorization, url, query, ttlSeconds));
            if (failingUrls.contains(url)) {
                throw new IllegalStateException("ZenDesk unavailable");
            }
            return tickets.getOrDefault(url, List.of());
        }

        @Override
        public boolean anyItemsInDuration(final String authorization, final String url, final String organization, final ChronoUnit duration, final ChronoUnit cached) {
            anyItemsCalls.add(authorization + "|" + url + "|" + organization + "|" + duration + "|" + cached);
            return anyItems;
        }

        @Override
        public ZenDeskTicket getTicket(final String authorization, final String url, final String ticketId, final int ttlSeconds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ZenDeskCommentsResponse getComments(final String authorization, final String url, final String ticketId, final int ttlSeconds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ZenDeskOrganizationItemResponse getOrganization(final String authorization, final String url, final String orgId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ZenDeskUserItemResponse getUser(final String authorization, final String url, final String userId) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * Drops the documents with a rejected ID.
     */
    private static final class RejectingRatingFilter implements RatingFilter {
        final Set<String> rejected = new HashSet<>();

        @Override
        public boolean contextMeetsRating(final RagDocumentContext<?> activity, final LocalConfigFilteredParent parsedArgs) {
            return !rejected.contains(activity.id());
        }

        @Override
        public <T> List<RagDocumentContext<T>> contextMeetsRating(final List<RagDocumentContext<T>> activity, final LocalConfigFilteredParent parsedArgs) {
            return activity.stream().filter(doc -> contextMeetsRating(doc, parsedArgs)).toList();
        }
    }

    /**
     * "Summarises" each document by prefixing it, and records the tool that asked for the summary.
     */
    private static final class PrefixSummarizer implements RagDocSummarizer {
        final List<String> tools = new CopyOnWriteArrayList<>();

        @Override
        public <T> RagDocumentContext<T> getDocumentSummary(final String toolName, final String contextLabel, final String datasource,
                                                            final RagDocumentContext<T> ragDoc, final Map<String, String> environmentSettings,
                                                            final LocalConfigSummarizer parsedArgs) {
            return ragDoc.updateDocument("Summary: " + ragDoc.document());
        }

        @Override
        public <T> List<RagDocumentContext<T>> getDocumentSummary(final String toolName, final String contextLabel, final String datasource,
                                                                  final List<RagDocumentContext<T>> ragDoc, final Map<String, String> environmentSettings,
                                                                  final LocalConfigSummarizer parsedArgs) {
            return ragDoc.stream().map(doc -> getDocumentSummary(toolName, contextLabel, datasource, doc, environmentSettings, parsedArgs)).toList();
        }

        @Override
        public <T> List<RagDocumentContext<T>> getDocumentSummary(final String toolName, final ContextLabelCallback<T> contextLabelCallback,
                                                                  final String datasource, final List<RagDocumentContext<T>> ragDoc,
                                                                  final Map<String, String> environmentSettings, final LocalConfigSummarizer parsedArgs) {
            tools.add(toolName);
            return ragDoc.stream().map(doc -> getDocumentSummary(toolName, contextLabelCallback.getContextLabel(null), datasource, doc, environmentSettings, parsedArgs)).toList();
        }
    }
}
