package secondbrain.domain.tools.zendesk;

import io.smallrye.common.annotation.Identifier;
import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import secondbrain.domain.args.ArgsAccessorSimple;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.date.DateParser;
import secondbrain.domain.encryption.Encryptor;
import secondbrain.domain.exceptionhandling.StandardExceptionMapping;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.objects.ToStringGenerator;
import secondbrain.domain.test.RecordingLlmClient;
import secondbrain.domain.test.TestArgs;
import secondbrain.domain.test.TestConfigUtil;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.tools.CommonArguments;
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
import java.time.temporal.TemporalUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import static java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME;
import static org.junit.jupiter.api.Assertions.*;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(ZenDeskStats.class)
@AddBeanClasses(ZenDeskStatsConfig.class)
@AddBeanClasses(ArgsAccessorSimple.class)
@AddBeanClasses(ValidateStringBlank.class)
@AddBeanClasses(StandardExceptionMapping.class)
class ZenDeskStatsTest {
    private static final String PROMPT = "How many tickets are open?";
    private static final String TOOL = "ZenDeskStats";
    private static final String AUTH = "Basic " + Base64.getEncoder().encodeToString("agent@example.com/token:secret".getBytes(StandardCharsets.UTF_8));
    private static final int DEFAULT_TTL = 60 * 60 * 24 * 90;

    private final FakeZenDeskClient zenDeskClient = new FakeZenDeskClient();
    private final RecordingLlmClient llmClient = new RecordingLlmClient();

    @Inject
    private ZenDeskStats tool;

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
    ToStringGenerator produceToStringGenerator() {
        return TestArgs.toStringGenerator("zendesk-stats", 23);
    }

    @BeforeEach
    void setUp() {
        TestConfigUtil.registerConfig(Map.of());
    }

    /**
     * Credentials as an encrypted URL and user and a plain text token.
     */
    private static Map<String, String> env() {
        return Map.of(
                "zendesk_url", "enc(https://acme.zendesk.com)",
                "zendesk_user", "enc(agent@example.com)",
                "zendesk_access_token", "secret");
    }

    private static List<ToolArgs> args(final String... extraNameValues) {
        final List<ToolArgs> arguments = new ArrayList<>();
        arguments.add(new ToolArgs(ZenDeskStats.ZENDESK_ORGANIZATION_ARG, "Acme", true));
        for (int i = 0; i < extraNameValues.length; i += 2) {
            arguments.add(new ToolArgs(extraNameValues[i], extraNameValues[i + 1], true));
        }
        return arguments;
    }

    private static ZenDeskTicket ticket(final String id, @Nullable final String status) {
        return new ZenDeskTicket(id, null, null, "Subject " + id, null, null, null, null, status, null);
    }

    private static String date(final TemporalUnit truncate, final int days, final int hours) {
        return OffsetDateTime.now(ZoneId.systemDefault()).truncatedTo(truncate).minusDays(days).minusHours(hours).format(ISO_OFFSET_DATE_TIME);
    }

    private static String endDate() {
        return OffsetDateTime.now(ZoneId.systemDefault()).truncatedTo(ChronoUnit.DAYS).plusDays(1).format(ISO_OFFSET_DATE_TIME);
    }

    /**
     * Asserts the recorded query matches the query calculated before or after the call, so the test does not fail if
     * the clock crosses an hour or day boundary.
     */
    private void assertComputedQuery(final String before, final String after) {
        final String query = zenDeskClient.calls.getFirst().query();
        assertTrue(query.equals(before) || query.equals(after), () -> "unexpected query " + query);
    }

    @Test
    void describesItself() {
        assertEquals(TOOL, tool.getName());
        assertEquals("ZenDesk Ticket Statistics", tool.getContextLabel());
        assertEquals("Returns a summary of ZenDesk ticket counts grouped by status for a given organisation.", tool.getDescription());
        assertEquals(List.of(ZenDeskStats.ZENDESK_ORGANIZATION_ARG, CommonArguments.DAYS_ARG, CommonArguments.HOURS_ARG,
                        CommonArguments.START_DATE, CommonArguments.END_DATE),
                tool.getArguments().stream().map(ToolArguments::name).toList());
    }

    @Test
    void getContextCountsTicketsByStatusInNameOrder() {
        zenDeskClient.tickets.addAll(List.of(ticket("1", "open"), ticket("2", "solved"), ticket("3", "open"),
                ticket("4", "new"), ticket("5", null), ticket("6", " ")));

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args());

        assertEquals(1, context.size());
        final RagDocumentContext<Void> document = context.getFirst();
        // Tickets with a missing or blank status are counted as unknown
        assertEquals("""
                ZenDesk Ticket Status Summary for organisation 'Acme':
                - new: 1
                - open: 2
                - solved: 1
                - unknown: 2
                Total tickets: 6
                """, document.document());
        assertEquals("Acme", document.id());
        assertEquals(TOOL, document.tool());
        assertEquals("ZenDesk Ticket Statistics", document.contextLabel());
        assertNull(document.link());
        assertTrue(llmClient.requests.isEmpty());
    }

    @Test
    void getContextSummarisesAnOrganisationWithoutTickets() {
        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args());

        assertEquals("ZenDesk Ticket Status Summary for organisation 'Acme':\nTotal tickets: 0\n", context.getFirst().document());
    }

    @Test
    void getContextSendsTheCredentialsAndDefaultTtlToTheClient() {
        tool.getContext(env(), List.of(PROMPT), args());

        final FakeZenDeskClient.Call call = zenDeskClient.calls.getFirst();
        assertEquals(AUTH, call.authorization());
        assertEquals("https://acme.zendesk.com", call.url());
        assertEquals(DEFAULT_TTL, call.ttlSeconds());
    }

    @Test
    void getContextUsesConfiguredCredentialsWhenTheContextHasNone() {
        TestConfigUtil.registerConfig(Map.of(
                "sb.zendesk.url", "https://config.zendesk.com",
                "sb.zendesk.user", "agent@example.com",
                "sb.zendesk.accesstoken", "secret"));

        tool.getContext(Map.of(), List.of(PROMPT), args());

        final FakeZenDeskClient.Call call = zenDeskClient.calls.getFirst();
        assertEquals(AUTH, call.authorization());
        assertEquals("https://config.zendesk.com", call.url());
    }

    @ParameterizedTest
    @CsvSource({"600, 600", "-5, 0", "abc, " + DEFAULT_TTL})
    void getContextPassesTheHistoryTtl(final String ttl, final int expected) {
        tool.getContext(env(), List.of(PROMPT), args(ZenDeskStats.ZENDESK_HISTORY_TTL_ARG, ttl));

        assertEquals(expected, zenDeskClient.calls.getFirst().ttlSeconds());
    }

    @Test
    void getContextQueriesTheSuppliedPeriod() {
        tool.getContext(env(), List.of(PROMPT), args(
                CommonArguments.START_DATE, "2024-03-01T00:00:00Z",
                CommonArguments.END_DATE, "2024-03-08T00:00:00Z"));

        assertEquals("type:ticket organization:Acme created>2024-03-01T00:00:00Z created<2024-03-08T00:00:00Z",
                zenDeskClient.calls.getFirst().query());
    }

    @Test
    void getContextQueriesFromTheStartDateOnly() {
        // A manual start date disables the computed date range, so there is no end date
        tool.getContext(env(), List.of(PROMPT), args(CommonArguments.START_DATE, "2024-03-01T00:00:00Z", CommonArguments.DAYS_ARG, "3"));

        assertEquals("type:ticket organization:Acme created>2024-03-01T00:00:00Z", zenDeskClient.calls.getFirst().query());
    }

    @Test
    void getContextQueriesUpToTheEndDateOnly() {
        tool.getContext(env(), List.of(PROMPT), args(CommonArguments.END_DATE, "2024-03-08T00:00:00Z"));

        assertEquals("type:ticket organization:Acme created<2024-03-08T00:00:00Z", zenDeskClient.calls.getFirst().query());
    }

    @Test
    void getContextDefaultsToTicketsSinceTheStartOfYesterday() {
        final String before = "type:ticket organization:Acme created>" + date(ChronoUnit.DAYS, 1, 0) + " created<" + endDate();

        tool.getContext(env(), List.of(PROMPT), args());

        assertComputedQuery(before, "type:ticket organization:Acme created>" + date(ChronoUnit.DAYS, 1, 0) + " created<" + endDate());
    }

    @Test
    void getContextComputesTheRangeFromDaysAndHours() {
        final String before = "type:ticket organization:Acme created>" + date(ChronoUnit.HOURS, 2, 5) + " created<" + endDate();

        tool.getContext(env(), List.of(PROMPT), args(CommonArguments.DAYS_ARG, "2", CommonArguments.HOURS_ARG, "5"));

        assertComputedQuery(before, "type:ticket organization:Acme created>" + date(ChronoUnit.HOURS, 2, 5) + " created<" + endDate());
    }

    @Test
    void getContextReadsTheOrganisationFromTheContext() {
        final Map<String, String> env = new HashMap<>(env());
        env.put(ZenDeskStats.ZENDESK_ORGANIZATION_ARG, "ContextOrg");

        final List<RagDocumentContext<Void>> context = tool.getContext(env, List.of(PROMPT), args());

        assertEquals("ContextOrg", context.getFirst().id());
        assertTrue(zenDeskClient.calls.getFirst().query().startsWith("type:ticket organization:ContextOrg "));
    }

    @Test
    void getContextReturnsNothingWithoutAnOrganisation() {
        assertEquals(List.of(), tool.getContext(env(), List.of(PROMPT), List.of()));
        assertTrue(zenDeskClient.calls.isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"zendesk_url", "zendesk_user", "zendesk_access_token"})
    void getContextReturnsNothingWhenACredentialIsMissing(final String missing) {
        final Map<String, String> env = new HashMap<>(env());
        env.remove(missing);

        assertEquals(List.of(), tool.getContext(env, List.of(PROMPT), args()));
        assertTrue(zenDeskClient.calls.isEmpty());
    }

    @Test
    void getContextReturnsNothingWhenTheClientFails() {
        zenDeskClient.fail = true;

        assertEquals(List.of(), tool.getContext(env(), List.of(PROMPT), args()));
        assertEquals(1, zenDeskClient.calls.size());
    }

    @Test
    void getContextHandlesAnEmptyPromptList() {
        assertEquals(1, tool.getContext(env(), List.of(), args()).size());
    }

    @Test
    void callAnswersFromTheTicketStatistics() {
        zenDeskClient.tickets.add(ticket("1", "open"));

        final RagMultiDocumentContext<Void> result = tool.call(env(), List.of(PROMPT), args());

        assertEquals("answer", result.getResponse());
        final RecordingLlmClient.Request request = llmClient.requests.getFirst();
        assertEquals(TOOL, request.tool());
        assertEquals(env(), request.environmentSettings());
        assertEquals(List.of(PROMPT), request.document().getPrompts());
        assertTrue(Objects.requireNonNull(request.document().instructions()).contains("You are given a summary of ZenDesk ticket counts"));
        assertEquals(List.of("ZenDesk Ticket Status Summary for organisation 'Acme':\n- open: 1\nTotal tickets: 1\n"),
                request.document().getIndividualContexts().stream().map(RagDocumentContext::document).toList());
    }

    @Test
    void callStillAsksTheLlmWhenThereIsNoContext() {
        final RagMultiDocumentContext<Void> result = tool.call(env(), List.of(PROMPT), List.of());

        assertEquals("answer", result.getResponse());
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
        assertEquals(31 * 23 + List.of(PROMPT).hashCode(), tool.contextHashCode(env(), List.of(PROMPT), args()));
    }

    /**
     * Records each getTickets call and returns the configured tickets. Other methods are not used by the tool.
     */
    private static final class FakeZenDeskClient implements ZenDeskClient {
        record Call(String authorization, String url, String query, int ttlSeconds) {
        }

        final List<ZenDeskTicket> tickets = new ArrayList<>();
        final List<Call> calls = new CopyOnWriteArrayList<>();
        boolean fail;

        @Override
        public List<ZenDeskTicket> getTickets(final String authorization, final String url, final String query, final int ttlSeconds) {
            calls.add(new Call(authorization, url, query, ttlSeconds));
            if (fail) {
                throw new IllegalStateException("ZenDesk unavailable");
            }
            return tickets;
        }

        @Override
        public boolean anyItemsInDuration(final String authorization, final String url, final String organization, final ChronoUnit duration, final ChronoUnit cached) {
            throw new UnsupportedOperationException();
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
}
