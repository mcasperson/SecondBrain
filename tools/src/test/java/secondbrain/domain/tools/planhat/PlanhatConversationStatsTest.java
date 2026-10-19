package secondbrain.domain.tools.planhat;

import io.smallrye.common.annotation.Identifier;
import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;
import secondbrain.domain.args.ArgsAccessorSimple;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.date.DateParser;
import secondbrain.domain.exceptionhandling.StandardExceptionMapping;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.objects.ToStringGenerator;
import secondbrain.domain.test.*;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.tools.CommonArguments;
import secondbrain.domain.validate.ValidateStringBlank;
import secondbrain.domain.web.ClientConstructor;
import secondbrain.infrastructure.llm.LlmClient;
import secondbrain.infrastructure.planhat.PlanHatClient;
import secondbrain.infrastructure.planhat.api.Conversation;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(PlanhatConversationStats.class)
@AddBeanClasses(PlanhatConversationStatsConfig.class)
@AddBeanClasses(ArgsAccessorSimple.class)
@AddBeanClasses(ValidateStringBlank.class)
@AddBeanClasses(StandardExceptionMapping.class)
// Conversations are built with null fields to match real responses
@SuppressWarnings("NullAway")
class PlanhatConversationStatsTest {
    private static final String PROMPT = "How many calls did we have?";
    private static final String TOOL = "PlanhatConversationStats";
    private static final String LABEL = "PlanHat Conversation Statistics";
    private static final String DEFAULT_URL = "https://api-us4.planhat.com";
    private static final int DEFAULT_TTL = 60 * 60 * 24 * 7;
    private static final String START = "2024-01-01T00:00:00Z";
    private static final String END = "2024-02-01T00:00:00Z";

    private final FakePlanHatClient planHatClient = new FakePlanHatClient();
    private final RecordingLlmClient llmClient = new RecordingLlmClient();
    private final Client client = mock(Client.class);

    @Inject
    private PlanhatConversationStats tool;

    @Produces
    @Preferred
    PlanHatClient producePlanHatClient() {
        return planHatClient;
    }

    @Produces
    @Preferred
    LlmClient produceLlmClient() {
        return llmClient;
    }

    @Produces
    ClientConstructor produceClientConstructor() {
        return new FixedClientConstructor(client);
    }

    @Produces
    @Identifier("everything")
    DateParser produceDateParser() {
        return ZonedDateTime::parse;
    }

    @Produces
    ToStringGenerator produceToStringGenerator() {
        return TestArgs.toStringGenerator("planhat-conversation-stats", 41);
    }

    @BeforeEach
    void setUp() {
        TestConfigUtil.registerConfig(Map.of());
    }

    private static Map<String, String> env() {
        return Map.of("planhat_token", "ph-token");
    }

    private static List<ToolArgs> args(final String... nameValues) {
        final List<ToolArgs> arguments = new ArrayList<>();
        arguments.add(new ToolArgs(PlanhatConversationStats.COMPANY_ID_ARG, "company-1", true));
        arguments.add(new ToolArgs(CommonArguments.START_DATE, START, true));
        arguments.add(new ToolArgs(CommonArguments.END_DATE, END, true));
        for (int i = 0; i < nameValues.length; i += 2) {
            arguments.add(new ToolArgs(nameValues[i], nameValues[i + 1], true));
        }
        return arguments;
    }

    private static Conversation conversation(final String id, final String type) {
        return new Conversation(id, "description", "snippet", "2024-01-05", "company-1", "Acme", "Subject", type, null);
    }

    @Test
    void describesItself() {
        assertEquals(TOOL, tool.getName());
        assertEquals(LABEL, tool.getContextLabel());
        assertEquals("Returns a summary of PlanHat conversation counts grouped by type for a given company.", tool.getDescription());
        assertEquals(List.of(PlanhatConversationStats.COMPANY_ID_ARG, CommonArguments.DAYS_ARG, CommonArguments.HOURS_ARG, CommonArguments.START_DATE, CommonArguments.END_DATE),
                tool.getArguments().stream().map(ToolArguments::name).toList());
    }

    @Test
    void getContextSummarisesConversationCountsByTypeInKeyOrder() {
        planHatClient.conversations.addAll(List.of(
                conversation("c1", "email"),
                conversation("c2", "call"),
                conversation("c3", null),
                conversation("c4", "email"),
                conversation("c5", " "),
                conversation("c6", "Call")));

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args());

        assertEquals(1, context.size());
        final RagDocumentContext<Void> document = context.getFirst();
        // Missing and blank types are counted as unknown, and the type names are case-sensitive
        assertEquals("""
                PlanHat Conversation Type Summary for company 'company-1':
                - Call: 1
                - call: 1
                - email: 2
                - unknown: 2
                Total conversations: 6
                """, document.document());
        assertEquals("company-1", document.id());
        assertEquals(TOOL, document.tool());
        assertEquals(LABEL, document.contextLabel());
        assertNull(document.link());
        assertTrue(llmClient.requests.isEmpty());
    }

    @Test
    void getContextReportsZeroConversations() {
        final RagDocumentContext<Void> document = tool.getContext(env(), List.of(PROMPT), args()).getFirst();

        assertEquals("""
                PlanHat Conversation Type Summary for company 'company-1':
                Total conversations: 0
                """, document.document());
    }

    @Test
    void getContextPassesTheRequestToTheClientAndClosesIt() {
        tool.getContext(env(), List.of(PROMPT), args());

        assertEquals(List.of(new FakePlanHatClient.ConversationCall(client, "company-1", DEFAULT_URL, "ph-token",
                ZonedDateTime.parse(START), ZonedDateTime.parse(END), DEFAULT_TTL)), planHatClient.conversationCalls);
        assertTrue(Mockito.mockingDetails(client).getInvocations().stream().anyMatch(i -> i.getMethod().getName().equals("close")));
    }

    @Test
    void getContextDefaultsToTheRangeFromYesterdayUntilTomorrow() {
        final List<ToolArgs> arguments = List.of(new ToolArgs(PlanhatConversationStats.COMPANY_ID_ARG, "company-1", true));

        final OffsetDateTime before = OffsetDateTime.now(ZoneId.systemDefault()).truncatedTo(ChronoUnit.DAYS);
        tool.getContext(env(), List.of(PROMPT), arguments);
        final OffsetDateTime after = OffsetDateTime.now(ZoneId.systemDefault()).truncatedTo(ChronoUnit.DAYS);

        final FakePlanHatClient.ConversationCall call = planHatClient.conversationCalls.getFirst();
        final OffsetDateTime start = Objects.requireNonNull(call.startDate()).toOffsetDateTime();
        final OffsetDateTime end = Objects.requireNonNull(call.endDate()).toOffsetDateTime();
        assertTrue(start.isEqual(before.minusDays(1)) || start.isEqual(after.minusDays(1)), () -> "start was " + start);
        assertTrue(end.isEqual(before.plusDays(1)) || end.isEqual(after.plusDays(1)), () -> "end was " + end);
    }

    @Test
    void getContextUsesTheDaysArgumentForTheStartOfTheRange() {
        final List<ToolArgs> arguments = List.of(
                new ToolArgs(PlanhatConversationStats.COMPANY_ID_ARG, "company-1", true),
                new ToolArgs(CommonArguments.DAYS_ARG, "3", true));

        final OffsetDateTime before = OffsetDateTime.now(ZoneId.systemDefault()).truncatedTo(ChronoUnit.DAYS);
        tool.getContext(env(), List.of(PROMPT), arguments);
        final OffsetDateTime after = OffsetDateTime.now(ZoneId.systemDefault()).truncatedTo(ChronoUnit.DAYS);

        final OffsetDateTime start = Objects.requireNonNull(planHatClient.conversationCalls.getFirst().startDate()).toOffsetDateTime();
        assertTrue(start.isEqual(before.minusDays(3)) || start.isEqual(after.minusDays(3)), () -> "start was " + start);
    }

    @ParameterizedTest
    @CsvSource({"60, 60", "-1, 0", "never, " + DEFAULT_TTL})
    void getContextPassesTheSearchTtl(final String ttl, final int expected) {
        tool.getContext(env(), List.of(PROMPT), args(PlanhatConversationStats.SEARCH_TTL_ARG, ttl));

        assertEquals(expected, planHatClient.conversationCalls.getFirst().ttlSeconds());
    }

    @Test
    void getContextPrefersTheConfiguredSettings() {
        TestConfigUtil.registerConfig(Map.of(
                "sb.planhat.company", "config-company",
                "sb.planhat.url", "https://config.planhat.com",
                "sb.planhat.accesstoken", "config-token",
                "sb.planhat.searchttl", "120"));

        tool.getContext(Map.of("planhat_token", "ph-token", PlanhatConversationStats.COMPANY_ID_ARG, "context-company"),
                List.of(PROMPT), args(PlanhatConversationStats.SEARCH_TTL_ARG, "60"));

        final FakePlanHatClient.ConversationCall call = planHatClient.conversationCalls.getFirst();
        assertEquals("config-company", call.companyId());
        assertEquals("https://config.planhat.com", call.url());
        assertEquals("config-token", call.token());
        assertEquals(120, call.ttlSeconds());
    }

    @Test
    void getContextReadsTheCompanyFromTheContextBeforeTheArgument() {
        planHatClient.conversations.add(conversation("c1", "call"));

        final RagDocumentContext<Void> document = tool.getContext(
                Map.of("planhat_token", "ph-token", PlanhatConversationStats.COMPANY_ID_ARG, "context-company"), List.of(PROMPT), args()).getFirst();

        assertEquals("context-company", planHatClient.conversationCalls.getFirst().companyId());
        assertEquals("context-company", document.id());
        assertTrue(document.document().startsWith("PlanHat Conversation Type Summary for company 'context-company':\n"));
    }

    @Test
    void getContextReturnsNothingWithoutACompany() {
        assertEquals(List.of(), tool.getContext(env(), List.of(PROMPT), List.of()));
        assertEquals(List.of(), tool.getContext(env(), List.of(PROMPT), List.of(new ToolArgs(PlanhatConversationStats.COMPANY_ID_ARG, " ", true))));
        assertTrue(planHatClient.conversationCalls.isEmpty());
    }

    @Test
    void getContextReturnsNothingWithoutAToken() {
        final Map<String, String> blankToken = new HashMap<>();
        blankToken.put("planhat_token", " ");

        assertEquals(List.of(), tool.getContext(blankToken, List.of(PROMPT), args()));
        assertEquals(List.of(), tool.getContext(Map.of(), List.of(PROMPT), args()));
        assertTrue(planHatClient.conversationCalls.isEmpty());
    }

    @Test
    void getContextReturnsNothingWithoutAUrl() {
        TestConfigUtil.registerConfig(Map.of("sb.planhat.url", " "));

        assertEquals(List.of(), tool.getContext(env(), List.of(PROMPT), args()));
        assertTrue(planHatClient.conversationCalls.isEmpty());
    }

    @Test
    void getContextReturnsNothingWhenTheClientFails() {
        planHatClient.fail = true;

        assertEquals(List.of(), tool.getContext(env(), List.of(PROMPT), args()));
        assertEquals(1, planHatClient.conversationCalls.size());
    }

    @Test
    void getContextHandlesAnEmptyPromptList() {
        assertEquals(1, tool.getContext(env(), List.of(), args()).size());
    }

    @Test
    void callAnswersFromTheConversationSummary() {
        planHatClient.conversations.add(conversation("c1", "call"));

        final RagMultiDocumentContext<Void> result = tool.call(env(), List.of(PROMPT), args());

        assertEquals("answer", result.getResponse());
        final RecordingLlmClient.Request request = llmClient.requests.getFirst();
        assertEquals(TOOL, request.tool());
        assertEquals(env(), request.environmentSettings());
        assertEquals(List.of(PROMPT), request.document().getPrompts());
        assertTrue(Objects.requireNonNull(request.document().instructions()).contains("You are given a summary of PlanHat conversation counts grouped by type"));
        assertEquals(List.of("company-1"), request.document().getIndividualContexts().stream().map(RagDocumentContext::id).toList());
    }

    @Test
    void callStillAsksTheLlmWhenThereIsNoContext() {
        assertEquals("answer", tool.call(Map.of(), List.of(PROMPT), List.of()).getResponse());
        assertEquals(List.of(), llmClient.requests.getFirst().document().getIndividualContexts());
    }

    @Test
    void callMapsLlmFailuresToInternalFailure() {
        llmClient.respondWith(doc -> {
            throw new IllegalStateException("LLM down");
        });

        final InternalFailure ex = assertThrows(InternalFailure.class, () -> tool.call(env(), List.of(PROMPT), args()));

        assertInstanceOf(IllegalStateException.class, ex.getCause());
    }

    @Test
    void contextHashCodeCombinesArgumentsAndPrompts() {
        assertEquals(31 * 41 + List.of(PROMPT).hashCode(), tool.contextHashCode(env(), List.of(PROMPT), args()));
    }
}
