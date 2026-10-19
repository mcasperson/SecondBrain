package secondbrain.domain.tools.planhat;

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
import secondbrain.domain.exceptionhandling.StandardExceptionMapping;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.objects.ToStringGenerator;
import secondbrain.domain.test.FakePlanHatClient;
import secondbrain.domain.test.FixedClientConstructor;
import secondbrain.domain.test.RecordingLlmClient;
import secondbrain.domain.test.TestArgs;
import secondbrain.domain.test.TestConfigUtil;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.validate.ValidateStringBlank;
import secondbrain.domain.web.ClientConstructor;
import secondbrain.infrastructure.llm.LlmClient;
import secondbrain.infrastructure.planhat.PlanHatClient;
import secondbrain.infrastructure.planhat.api.Opportunity;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(PlanhatOpportunities.class)
@AddBeanClasses(PlanhatOpportunitiesConfig.class)
@AddBeanClasses(ArgsAccessorSimple.class)
@AddBeanClasses(ValidateStringBlank.class)
@AddBeanClasses(StandardExceptionMapping.class)
// Opportunities are built with null fields to match real responses
@SuppressWarnings("NullAway")
class PlanhatOpportunitiesTest {
    private static final String PROMPT = "Which deals are closing?";
    private static final String TOOL = "PlanhatOpportunities";
    private static final String DEFAULT_URL = "https://api-us4.planhat.com";
    private static final int DEFAULT_TTL = 60 * 60 * 24 * 7;

    private final FakePlanHatClient planHatClient = new FakePlanHatClient();
    private final RecordingLlmClient llmClient = new RecordingLlmClient();
    private final Client client = mock(Client.class);

    @Inject
    private PlanhatOpportunities tool;

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
    ToStringGenerator produceToStringGenerator() {
        return TestArgs.toStringGenerator("planhat-opportunities", 37);
    }

    @BeforeEach
    void setUp() {
        TestConfigUtil.registerConfig(Map.of());
    }

    private static Map<String, String> env() {
        return Map.of("planhat_token", "ph-token");
    }

    private static List<ToolArgs> args(final String... nameValues) {
        final List<ToolArgs> arguments = new java.util.ArrayList<>();
        arguments.add(new ToolArgs(PlanhatOpportunities.COMPANY_ID_ARG, "company-1", true));
        for (int i = 0; i < nameValues.length; i += 2) {
            arguments.add(new ToolArgs(nameValues[i], nameValues[i + 1], true));
        }
        return arguments;
    }

    private static Opportunity opportunity(final String id, final Map<String, Object> custom) {
        return new Opportunity(id, "Open", "company-1", "Acme", "Renewal", "owner-1", "source-1", 100.5, 1206.0,
                "Negotiation", "2024-07-01", "2024-08-01", "2024-01-01", "2024-02-01", custom);
    }

    @Test
    void describesItself() {
        assertEquals(TOOL, tool.getName());
        assertEquals("PlanHat Opportunities", tool.getContextLabel());
        assertEquals("Returns PlanHat opportunities for a given PlanHat company ID.", tool.getDescription());
        assertEquals(List.of(PlanhatOpportunities.COMPANY_ID_ARG), tool.getArguments().stream().map(ToolArguments::name).toList());
    }

    @Test
    void getContextConvertsEachOpportunityToADocument() {
        final Map<String, Object> custom = new LinkedHashMap<>();
        custom.put("Region", "EMEA");
        custom.put("Seats", 25);
        custom.put("Empty", null);
        planHatClient.opportunities.add(opportunity("opp-1", custom));

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args());

        assertEquals(1, context.size());
        final RagDocumentContext<Void> document = context.getFirst();
        // Null custom values are written as empty strings
        assertEquals("""
                PlanHat Opportunity:
                - ID: opp-1
                - Title: Renewal
                - Status: Open
                - Sales Stage: Negotiation
                - Company ID: company-1
                - Company Name: Acme
                - MRR: 100.5
                - ARR: 1206.0
                - Owner ID: owner-1
                - Landing Date: 2024-07-01
                - Close Date: 2024-08-01
                - Created At: 2024-01-01
                - Updated At: 2024-02-01
                - Custom Fields:
                  - Region: EMEA
                  - Seats: 25
                  - Empty:\s
                """, document.document());
        assertEquals("opp-1", document.id());
        assertEquals(TOOL, document.tool());
        assertEquals("PlanHat Opportunities", document.contextLabel());
        assertNull(document.link());
        assertTrue(llmClient.requests.isEmpty());
    }

    @Test
    void getContextUsesDefaultsForMissingValues() {
        planHatClient.opportunities.add(new Opportunity(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));

        final RagDocumentContext<Void> document = tool.getContext(env(), List.of(PROMPT), args()).getFirst();

        // Without custom fields the custom section is left out
        assertEquals("""
                PlanHat Opportunity:
                - ID:\s
                - Title:\s
                - Status:\s
                - Sales Stage:\s
                - Company ID:\s
                - Company Name:\s
                - MRR: 0.0
                - ARR: 0.0
                - Owner ID:\s
                - Landing Date:\s
                - Close Date:\s
                - Created At:\s
                - Updated At:\s
                """, document.document());
        assertNull(document.id());
    }

    @Test
    void getContextReturnsADocumentPerOpportunity() {
        planHatClient.opportunities.add(opportunity("opp-1", Map.of()));
        planHatClient.opportunities.add(opportunity("opp-2", Map.of()));

        assertEquals(List.of("opp-1", "opp-2"), tool.getContext(env(), List.of(PROMPT), args()).stream().map(RagDocumentContext::id).toList());
    }

    @Test
    void getContextPassesTheRequestToTheClientAndClosesIt() {
        tool.getContext(env(), List.of(PROMPT), args());

        assertEquals(List.of(new FakePlanHatClient.Call("getOpportunities", client, "company-1", DEFAULT_URL, "ph-token", DEFAULT_TTL)),
                planHatClient.calls);
        assertTrue(Mockito.mockingDetails(client).getInvocations().stream().anyMatch(i -> i.getMethod().getName().equals("close")));
    }

    @ParameterizedTest
    @CsvSource({"60, 60", "-1, 0", "never, " + DEFAULT_TTL})
    void getContextPassesTheSearchTtl(final String ttl, final int expected) {
        tool.getContext(env(), List.of(PROMPT), args(PlanhatOpportunities.SEARCH_TTL_ARG, ttl));

        assertEquals(expected, planHatClient.calls.getFirst().ttlSeconds());
    }

    @Test
    void getContextPrefersTheConfiguredSettings() {
        TestConfigUtil.registerConfig(Map.of(
                "sb.planhat.company", "config-company",
                "sb.planhat.url", "https://config.planhat.com",
                "sb.planhat.accesstoken", "config-token",
                "sb.planhat.searchttl", "120"));

        tool.getContext(Map.of("planhat_token", "ph-token", "planhat_url", "https://context.planhat.com",
                PlanhatOpportunities.COMPANY_ID_ARG, "context-company"), List.of(PROMPT), args(PlanhatOpportunities.SEARCH_TTL_ARG, "60"));

        assertEquals(List.of(new FakePlanHatClient.Call("getOpportunities", client, "config-company", "https://config.planhat.com", "config-token", 120)),
                planHatClient.calls);
    }

    @Test
    void getContextReadsTheCompanyFromTheContextBeforeTheArgument() {
        tool.getContext(Map.of("planhat_token", "ph-token", PlanhatOpportunities.COMPANY_ID_ARG, "context-company"), List.of(PROMPT), args());

        assertEquals("context-company", planHatClient.calls.getFirst().companyId());
    }

    @Test
    void getContextIgnoresTheContextUrlBecauseTheConfigHasADefault() {
        // Looks like a bug: sb.planhat.url has a default value, so the planhat_url context value is never used
        tool.getContext(Map.of("planhat_token", "ph-token", "planhat_url", "https://context.planhat.com"), List.of(PROMPT), args());

        assertEquals(DEFAULT_URL, planHatClient.calls.getFirst().url());
    }

    @Test
    void getContextReturnsNothingWithoutACompany() {
        assertEquals(List.of(), tool.getContext(env(), List.of(PROMPT), List.of()));
        assertTrue(planHatClient.calls.isEmpty());
    }

    @Test
    void getContextReturnsNothingWithoutAToken() {
        final Map<String, String> env = new HashMap<>();
        env.put("planhat_token", " ");

        assertEquals(List.of(), tool.getContext(env, List.of(PROMPT), args()));
        assertEquals(List.of(), tool.getContext(Map.of(), List.of(PROMPT), args()));
        assertTrue(planHatClient.calls.isEmpty());
    }

    @Test
    void getContextReturnsNothingWithoutAUrl() {
        TestConfigUtil.registerConfig(Map.of("sb.planhat.url", " "));

        assertEquals(List.of(), tool.getContext(env(), List.of(PROMPT), args()));
        assertTrue(planHatClient.calls.isEmpty());
    }

    @Test
    void getContextReturnsNothingWhenTheClientFails() {
        planHatClient.fail = true;

        assertEquals(List.of(), tool.getContext(env(), List.of(PROMPT), args()));
        assertEquals(1, planHatClient.calls.size());
    }

    @Test
    void getContextHandlesAnEmptyPromptList() {
        planHatClient.opportunities.add(opportunity("opp-1", Map.of()));

        assertEquals(1, tool.getContext(env(), List.of(), args()).size());
    }

    @Test
    void callAnswersFromTheOpportunities() {
        planHatClient.opportunities.add(opportunity("opp-1", Map.of()));

        final RagMultiDocumentContext<Void> result = tool.call(env(), List.of(PROMPT), args());

        assertEquals("answer", result.getResponse());
        final RecordingLlmClient.Request request = llmClient.requests.getFirst();
        assertEquals(TOOL, request.tool());
        assertEquals(env(), request.environmentSettings());
        assertEquals(List.of(PROMPT), request.document().getPrompts());
        assertTrue(Objects.requireNonNull(request.document().instructions()).contains("You are given PlanHat opportunity details"));
        assertEquals(List.of("opp-1"), request.document().getIndividualContexts().stream().map(RagDocumentContext::id).toList());
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
        assertEquals(31 * 37 + List.of(PROMPT).hashCode(), tool.contextHashCode(env(), List.of(PROMPT), args()));
    }
}
