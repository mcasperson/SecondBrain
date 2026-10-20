package secondbrain.domain.tools.planhat;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;
import secondbrain.domain.annotations.PropertyLabelReaderNullOnFailure;
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
import secondbrain.domain.tooldefs.MetaObjectResult;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.validate.ValidateStringBlank;
import secondbrain.domain.web.ClientConstructor;
import secondbrain.infrastructure.llm.LlmClient;
import secondbrain.infrastructure.planhat.PlanHatClient;
import secondbrain.infrastructure.planhat.api.Company;
import secondbrain.infrastructure.planhat.api.PlanHatUser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(PlanHatUsage.class)
@AddBeanClasses(PlanHatUsageConfig.class)
@AddBeanClasses(ArgsAccessorSimple.class)
@AddBeanClasses(ValidateStringBlank.class)
@AddBeanClasses(StandardExceptionMapping.class)
@AddBeanClasses(PropertyLabelReaderNullOnFailure.class)
class PlanHatUsageTest {
    private static final String PROMPT = "How much is Acme using the platform?";
    private static final String TOOL = "PlanHatUsage";
    private static final String LABEL = "PlanHat Metadata";
    private static final String DEFAULT_URL = "https://api-us4.planhat.com";
    private static final String SECOND_URL = "https://api-eu.planhat.com";
    private static final int DEFAULT_TTL = 60 * 60 * 24 * 7;

    private final FakePlanHatClient planHatClient = new FakePlanHatClient();
    private final RecordingLlmClient llmClient = new RecordingLlmClient();
    private final Client client = mock(Client.class);

    @Inject
    private PlanHatUsage tool;

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
        return TestArgs.toStringGenerator("planhat-usage", 17);
    }

    @BeforeEach
    void setUp() {
        TestConfigUtil.registerConfig(Map.of());
        planHatClient.usersById.put("owner-1", new PlanHatUser("owner-1", "Olivia", "Owner"));
        planHatClient.usersById.put("user-2", new PlanHatUser("user-2", "Casey", null));
    }

    private static Map<String, String> env() {
        return Map.of("planhat_usage_token", "token-1");
    }

    private static Map<String, String> envWithSecondInstance(final boolean includeFirstToken) {
        final Map<String, String> env = new HashMap<>();
        if (includeFirstToken) {
            env.put("planhat_usage_token", "token-1");
        }
        env.put("planhat_url2", SECOND_URL);
        env.put("planhat_token2", "token-2");
        return env;
    }

    private static List<ToolArgs> args(final String... extraNameValues) {
        final List<ToolArgs> arguments = new ArrayList<>();
        arguments.add(new ToolArgs(PlanHatUsage.COMPANY_ID_ARGS, "co-1", true));
        for (int i = 0; i < extraNameValues.length; i += 2) {
            arguments.add(new ToolArgs(extraNameValues[i], extraNameValues[i + 1], true));
        }
        return arguments;
    }

    /**
     * A company with every labelled property set, so the owner and property documents are generated.
     */
    private static Company fullCompany() {
        return company("owner-1", "2025-01-01", 7);
    }

    /**
     * A company without labelled properties, so only the usage, custom and custom person documents are generated.
     */
    private static Company bareCompany() {
        return company(null, null, null);
    }

    private static Company company(@Nullable final String owner, @Nullable final String renewalDate, @Nullable final Integer health) {
        return new Company("co-1", "Acme", owner, renewalDate, health,
                Map.of("logins", 42, "deploys", 3),
                Map.of("Tier", "Gold", "CSM", "user-2", "Seats", 25));
    }

    private static List<String> summary(final List<RagDocumentContext<Void>> context) {
        return context.stream().map(doc -> doc.contextLabel() + "|" + doc.document() + "|" + doc.id()).toList();
    }

    private List<FakePlanHatClient.Call> callsTo(final String method) {
        return planHatClient.calls.stream().filter(call -> call.method().equals(method)).toList();
    }

    @Test
    void describesItself() {
        assertEquals(TOOL, tool.getName());
        assertEquals(LABEL, tool.getContextLabel());
        assertEquals("Queries PlanHat for company platform usage", tool.getDescription());
        assertEquals(List.of(PlanHatUsage.COMPANY_ID_ARGS), tool.getArguments().stream().map(ToolArguments::name).toList());
    }

    @Test
    void getContextReturnsUsageCustomPeopleAndPropertyDocumentsInOrder() {
        planHatClient.companiesByUrl.put(DEFAULT_URL, fullCompany());

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args(
                PlanHatUsage.PLANHAT_USAGE_NAME_1_ARG, "Logins", PlanHatUsage.PLANHAT_USAGE_ID_1_ARG, "logins",
                PlanHatUsage.PLANHAT_USAGE_NAME_2_ARG, "Builds", PlanHatUsage.PLANHAT_USAGE_ID_2_ARG, "builds",
                PlanHatUsage.PLANHAT_CUSTOM_1_ARG, "Tier",
                PlanHatUsage.PLANHAT_CUSTOM_2_ARG, "Region",
                PlanHatUsage.PLANHAT_CUSTOM_3_ARG, "Seats",
                PlanHatUsage.PLANHAT_CUSTOM_PERSON_1_ARG, "CSM"));

        // Missing usage values default to 0, missing custom values to an empty string, and the custom person and
        // the owner are resolved to the user's full name
        assertEquals(List.of(
                LABEL + " Acme Logins|42|co-1:logins",
                LABEL + " Acme Builds|0|co-1:builds",
                LABEL + " Acme Tier|Gold|co-1:Tier",
                LABEL + " Acme Region||co-1:Region",
                LABEL + " Acme Seats|25|co-1:Seats",
                LABEL + " Acme CSM|Casey|co-1:CSM",
                LABEL + " Acme Renewal Date|2025-01-01|co-1:Renewal Date",
                LABEL + " Acme Health Score|7|co-1:Health Score",
                LABEL + " Acme Owner|Olivia Owner|co-1:Owner"), summary(context));
        assertTrue(context.stream().allMatch(doc -> TOOL.equals(doc.tool())));
        assertTrue(llmClient.requests.isEmpty());
    }

    @Test
    void getContextAddsMetadataForEachDocument() {
        planHatClient.companiesByUrl.put(DEFAULT_URL, fullCompany());

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args(
                PlanHatUsage.PLANHAT_USAGE_NAME_1_ARG, "Logins", PlanHatUsage.PLANHAT_USAGE_ID_1_ARG, "logins",
                PlanHatUsage.PLANHAT_CUSTOM_1_ARG, "Tier",
                PlanHatUsage.PLANHAT_CUSTOM_PERSON_1_ARG, "CSM"));

        assertEquals(List.of(
                        List.of(new MetaObjectResult("Logins", "42", "co-1", TOOL)),
                        List.of(new MetaObjectResult("Tier", "Gold", "co-1", TOOL)),
                        List.of(new MetaObjectResult("CSM", "Casey", "co-1", TOOL)),
                        List.of(new MetaObjectResult("Renewal Date", "2025-01-01", "co-1", TOOL)),
                        List.of(new MetaObjectResult("Health Score", "7", "co-1", TOOL)),
                        List.of(new MetaObjectResult("Owner", "Olivia Owner", "co-1", TOOL))),
                context.stream().map(RagDocumentContext::getMetadata).toList());
    }

    @Test
    void getContextUsesAllFiveUsageAndCustomSlots() {
        planHatClient.companiesByUrl.put(DEFAULT_URL, bareCompany());

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args(
                PlanHatUsage.PLANHAT_USAGE_NAME_5_ARG, "Deploys", PlanHatUsage.PLANHAT_USAGE_ID_5_ARG, "deploys",
                PlanHatUsage.PLANHAT_CUSTOM_5_ARG, "Tier",
                PlanHatUsage.PLANHAT_CUSTOM_PERSON_5_ARG, "CSM"));

        assertEquals(List.of(
                LABEL + " Acme Deploys|3|co-1:deploys",
                LABEL + " Acme Tier|Gold|co-1:Tier",
                LABEL + " Acme CSM|Casey|co-1:CSM"), summary(context));
    }

    @Test
    void getContextSkipsUsageWithoutBothANameAndAnId() {
        planHatClient.companiesByUrl.put(DEFAULT_URL, bareCompany());

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args(
                PlanHatUsage.PLANHAT_USAGE_NAME_1_ARG, "Logins",
                PlanHatUsage.PLANHAT_USAGE_ID_2_ARG, "deploys"));

        assertEquals(List.of(), context);
    }

    @Test
    void getContextSkipsCustomPersonsWithoutAUserIdOrAKnownUser() {
        planHatClient.companiesByUrl.put(DEFAULT_URL, new Company("co-1", "Acme", null, null, null, Map.of(),
                Map.of("CSM", "user-2", "AE", "ghost", "Empty", " ")));

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args(
                PlanHatUsage.PLANHAT_CUSTOM_PERSON_1_ARG, "CSM",
                PlanHatUsage.PLANHAT_CUSTOM_PERSON_2_ARG, "AE",
                PlanHatUsage.PLANHAT_CUSTOM_PERSON_3_ARG, "Missing",
                PlanHatUsage.PLANHAT_CUSTOM_PERSON_4_ARG, "Empty"));

        // "AE" points to a user that can not be found, while "Missing" and "Empty" have no user ID to look up
        assertEquals(List.of(LABEL + " Acme CSM|Casey|co-1:CSM"), summary(context));
        assertEquals(List.of("ghost", "user-2"), callsTo("getUser").stream().map(FakePlanHatClient.Call::companyId).sorted().toList());
    }

    @Test
    void getContextSkipsAnOwnerThatCanNotBeFound() {
        planHatClient.companiesByUrl.put(DEFAULT_URL, company("ghost", "2025-01-01", null));

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args());

        assertEquals(List.of(LABEL + " Acme Renewal Date|2025-01-01|co-1:Renewal Date"), summary(context));
        assertEquals(List.of(new FakePlanHatClient.Call("getUser", client, "ghost", DEFAULT_URL, "token-1", DEFAULT_TTL)), callsTo("getUser"));
    }

    @Test
    void getContextPassesTheRequestToTheClientAndClosesIt() {
        planHatClient.companiesByUrl.put(DEFAULT_URL, fullCompany());

        tool.getContext(env(), List.of(PROMPT), args());

        assertEquals(List.of(
                new FakePlanHatClient.Call("getCompany", client, "co-1", DEFAULT_URL, "token-1", DEFAULT_TTL),
                new FakePlanHatClient.Call("getUser", client, "owner-1", DEFAULT_URL, "token-1", DEFAULT_TTL)), planHatClient.calls);
        assertTrue(Mockito.mockingDetails(client).getInvocations().stream().anyMatch(i -> i.getMethod().getName().equals("close")));
    }

    @ParameterizedTest
    @CsvSource({"60, 60", "0, 0", "-1, 0"})
    void getContextPassesTheSearchTtl(final String ttl, final int expected) {
        planHatClient.companiesByUrl.put(DEFAULT_URL, bareCompany());

        tool.getContext(env(), List.of(PROMPT), args(PlanHatUsage.SEARCH_TTL_ARG, ttl));

        assertEquals(expected, planHatClient.calls.getFirst().ttlSeconds());
    }

    @Test
    void getContextReturnsNothingForAnInvalidSearchTtl() {
        planHatClient.companiesByUrl.put(DEFAULT_URL, bareCompany());

        assertEquals(List.of(), tool.getContext(env(), List.of(PROMPT), args(PlanHatUsage.SEARCH_TTL_ARG, "never",
                PlanHatUsage.PLANHAT_CUSTOM_1_ARG, "Tier")));
    }

    @Test
    void getContextFallsBackToTheSecondInstanceWhenTheFirstFails() {
        planHatClient.companiesByUrl.put(SECOND_URL, fullCompany());

        final List<RagDocumentContext<Void>> context = tool.getContext(envWithSecondInstance(true), List.of(PROMPT), args(
                PlanHatUsage.PLANHAT_CUSTOM_1_ARG, "Tier"));

        assertEquals(LABEL + " Acme Tier|Gold|co-1:Tier", summary(context).getFirst());
        assertEquals(List.of(
                new FakePlanHatClient.Call("getCompany", client, "co-1", DEFAULT_URL, "token-1", DEFAULT_TTL),
                new FakePlanHatClient.Call("getCompany", client, "co-1", SECOND_URL, "token-2", DEFAULT_TTL)), callsTo("getCompany"));
        // People are always looked up in the first PlanHat instance
        assertEquals(List.of(new FakePlanHatClient.Call("getUser", client, "owner-1", DEFAULT_URL, "token-1", DEFAULT_TTL)), callsTo("getUser"));
    }

    @Test
    void getContextStopsAtTheFirstInstanceThatFindsTheCompany() {
        planHatClient.companiesByUrl.put(DEFAULT_URL, bareCompany());
        planHatClient.companiesByUrl.put(SECOND_URL, fullCompany());

        final List<RagDocumentContext<Void>> context = tool.getContext(envWithSecondInstance(true), List.of(PROMPT), args());

        // The bare company from the first instance has no property documents
        assertEquals(List.of(), context);
        assertEquals(List.of(DEFAULT_URL), callsTo("getCompany").stream().map(FakePlanHatClient.Call::url).toList());
    }

    @Test
    void getContextUsesOnlyTheSecondInstanceWhenTheFirstHasNoToken() {
        planHatClient.companiesByUrl.put(SECOND_URL, bareCompany());

        final List<RagDocumentContext<Void>> context = tool.getContext(envWithSecondInstance(false), List.of(PROMPT), args(
                PlanHatUsage.PLANHAT_CUSTOM_PERSON_1_ARG, "CSM"));

        assertEquals(List.of(LABEL + " Acme CSM|Casey|co-1:CSM"), summary(context));
        assertEquals(List.of(
                new FakePlanHatClient.Call("getCompany", client, "co-1", SECOND_URL, "token-2", DEFAULT_TTL),
                new FakePlanHatClient.Call("getUser", client, "user-2", SECOND_URL, "token-2", DEFAULT_TTL)), planHatClient.calls);
    }

    @Test
    void getContextReadsTheTokenFromTheConfiguration() {
        TestConfigUtil.registerConfig(Map.of("sb.planhat.accesstoken", "config-token", "sb.planhat.url", "https://config.planhat.com"));
        planHatClient.companiesByUrl.put("https://config.planhat.com", bareCompany());

        tool.getContext(Map.of("planhat_usage_token", "context-token"), List.of(PROMPT), args());

        assertEquals(List.of(new FakePlanHatClient.Call("getCompany", client, "co-1", "https://config.planhat.com", "config-token", DEFAULT_TTL)),
                planHatClient.calls);
    }

    @Test
    void getContextReturnsNothingWithoutACompany() {
        planHatClient.companiesByUrl.put(DEFAULT_URL, fullCompany());

        assertEquals(List.of(), tool.getContext(env(), List.of(PROMPT), List.of()));
        assertTrue(planHatClient.calls.isEmpty());
    }

    @Test
    void getContextReturnsNothingWithoutAToken() {
        planHatClient.companiesByUrl.put(DEFAULT_URL, fullCompany());

        assertEquals(List.of(), tool.getContext(Map.of(), List.of(PROMPT), args()));
        assertTrue(planHatClient.calls.isEmpty());
    }

    @Test
    void getContextReturnsNothingWhenTheCompanyCanNotBeFound() {
        assertEquals(List.of(), tool.getContext(env(), List.of(PROMPT), args(PlanHatUsage.PLANHAT_CUSTOM_1_ARG, "Tier")));
        assertEquals(1, planHatClient.calls.size());
    }

    @Test
    void getContextHandlesAnEmptyPromptList() {
        planHatClient.companiesByUrl.put(DEFAULT_URL, bareCompany());

        assertEquals(List.of(LABEL + " Acme Tier|Gold|co-1:Tier"),
                summary(tool.getContext(env(), List.of(), args(PlanHatUsage.PLANHAT_CUSTOM_1_ARG, "Tier"))));
    }

    @Test
    void callAnswersFromTheUsageContext() {
        planHatClient.companiesByUrl.put(DEFAULT_URL, bareCompany());

        final RagMultiDocumentContext<Void> result = tool.call(env(), List.of(PROMPT), args(PlanHatUsage.PLANHAT_CUSTOM_1_ARG, "Tier"));

        assertEquals("answer", result.getResponse());
        final RecordingLlmClient.Request request = llmClient.requests.getFirst();
        assertEquals(TOOL, request.tool());
        assertEquals(env(), request.environmentSettings());
        assertEquals(List.of(PROMPT), request.document().getPrompts());
        assertTrue(Objects.requireNonNull(request.document().instructions()).contains("You are given details of platform usage associated with a PlanHat company."));
        assertEquals(List.of("Gold"), request.document().getIndividualContexts().stream().map(RagDocumentContext::document).toList());
    }

    @Test
    void callStillAsksTheLlmWhenTheCompanyCanNotBeFound() {
        final RagMultiDocumentContext<Void> result = tool.call(env(), List.of(PROMPT), args());

        assertEquals("answer", result.getResponse());
        assertEquals(List.of(), llmClient.requests.getFirst().document().getIndividualContexts());
    }

    @Test
    void callRequiresACompany() {
        final InternalFailure ex = assertThrows(InternalFailure.class, () -> tool.call(env(), List.of(PROMPT), List.of()));

        assertEquals("You must provide a company to query", ex.getMessage());
        assertTrue(llmClient.requests.isEmpty());
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
        assertEquals(31 * 17 + List.of(PROMPT).hashCode(), tool.contextHashCode(env(), List.of(PROMPT), args()));
        assertEquals(31 * 17 + List.of().hashCode(), tool.contextHashCode(env(), List.of(), args()));
    }
}
