package secondbrain.domain.tools.salesforce;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import secondbrain.domain.args.ArgsAccessorSimple;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.exceptionhandling.StandardExceptionMapping;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.objects.ToStringGenerator;
import secondbrain.domain.test.RecordingLlmClient;
import secondbrain.domain.test.TestArgs;
import secondbrain.domain.test.TestConfigUtil;
import secondbrain.domain.tooldefs.IntermediateResult;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.validate.ValidateStringBlank;
import secondbrain.infrastructure.llm.LlmClient;
import secondbrain.infrastructure.salesforce.SalesforceClient;
import secondbrain.infrastructure.salesforce.api.*;

import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(SalesforceAccountDetails.class)
@AddBeanClasses(SalesforceAccountDetailsConfig.class)
@AddBeanClasses(ArgsAccessorSimple.class)
@AddBeanClasses(ValidateStringBlank.class)
@AddBeanClasses(StandardExceptionMapping.class)
@AddBeanClasses(Loggers.class)
class SalesforceAccountDetailsTest {
    private static final String PROMPT = "Who owns the account?";
    private static final String TOOL = "SalesforceAccountDetails";
    private static final String LABEL = "Salesforce Account Details";
    private static final String ACCOUNT_ID = "001Qq00000a53mwIAA";
    private static final String EXPECTED_DOCUMENT = """
            Salesforce Account 001Qq00000a53mwIAA:
            - Account Name: Foodstuffs North Island
            - Account Owner Name: Sophia Gazi
            - Account Owner Email: sophia.gazi@octopus.com
            - Billing Street: 35 Landing Dr
            - Billing City: Mangere
            - Billing State:\s
            - Billing Postal Code:\s
            - Billing Country: NZ
            - Industry: Grocery Retail
            - Number Of Employees: 24000
            """;

    private final FakeSalesforceClient salesforceClient = new FakeSalesforceClient();
    private final RecordingLlmClient llmClient = new RecordingLlmClient();

    @Inject
    private SalesforceAccountDetails tool;

    @Produces
    @Preferred
    SalesforceClient produceSalesforceClient() {
        return salesforceClient;
    }

    @Produces
    @Preferred
    LlmClient produceLlmClient() {
        return llmClient;
    }

    @Produces
    ToStringGenerator produceToStringGenerator() {
        return TestArgs.toStringGenerator("salesforce-account-details", 17);
    }

    @BeforeEach
    void setUp() {
        TestConfigUtil.registerConfig(Map.of(
                "sb.salesforce.clientid", "client-id",
                "sb.salesforce.clientsecret", "client-secret"));
    }

    private static List<ToolArgs> args(final String accountId) {
        return List.of(new ToolArgs(SalesforceAccountDetails.ACCOUNT_ID_ARG, accountId, true));
    }

    private static SalesforceAccountRecord account() {
        return new SalesforceAccountRecord(ACCOUNT_ID, "Foodstuffs North Island",
                new SalesforceAccountOwner("Sophia Gazi", "sophia.gazi@octopus.com"),
                "35 Landing Dr", "Mangere", null, null, "NZ", "Grocery Retail", 24000);
    }

    @Test
    void describesItself() {
        assertEquals(TOOL, tool.getName());
        assertEquals(LABEL, tool.getContextLabel());
        assertEquals("Returns the name, owner name, owner email, billing address, industry, and number of employees of a Salesforce account.", tool.getDescription());
        assertEquals(List.of(SalesforceAccountDetails.ACCOUNT_ID_ARG, SalesforceAccountDetails.DOMAIN_ARG),
                tool.getArguments().stream().map(ToolArguments::name).toList());
    }

    @Test
    void getContextConvertsTheAccountToADocument() {
        salesforceClient.result = Optional.of(account());

        final List<RagDocumentContext<Void>> context = tool.getContext(Map.of(), List.of(PROMPT), args(ACCOUNT_ID));

        assertEquals(1, context.size());
        final RagDocumentContext<Void> document = context.getFirst();
        assertEquals(EXPECTED_DOCUMENT, document.document());
        assertEquals(ACCOUNT_ID, document.id());
        assertEquals(TOOL, document.tool());
        assertEquals(LABEL, document.contextLabel());
        assertNull(document.link());
        assertEquals(List.of(new IntermediateResult(EXPECTED_DOCUMENT, "Data-SalesforceAccountDetails-" + ACCOUNT_ID + ".txt")),
                document.getIntermediateResults());
        assertEquals(List.of("client-id:client-secret"), salesforceClient.tokenRequests);
        assertEquals(List.of("mock_token:" + ACCOUNT_ID), salesforceClient.accountRequests);
        assertTrue(llmClient.requests.isEmpty());
    }

    @Test
    void getContextLinksToTheAccountWhenADomainIsConfigured() {
        TestConfigUtil.registerConfig(Map.of("sb.salesforce.domain", "octopusdeploy"));
        salesforceClient.result = Optional.of(account());

        final RagDocumentContext<Void> document = tool.getContext(Map.of(), List.of(PROMPT), args(ACCOUNT_ID)).getFirst();

        assertEquals("[Salesforce Account Foodstuffs North Island](https://octopusdeploy.lightning.force.com/lightning/r/Account/" + ACCOUNT_ID + "/view)",
                document.link());
    }

    @Test
    void getContextUsesDefaultsForMissingValues() {
        salesforceClient.result = Optional.of(new SalesforceAccountRecord(ACCOUNT_ID, null, null, null, null, null, null, null, null, null));

        assertEquals("""
                Salesforce Account 001Qq00000a53mwIAA:
                - Account Name:\s
                - Account Owner Name:\s
                - Account Owner Email:\s
                - Billing Street:\s
                - Billing City:\s
                - Billing State:\s
                - Billing Postal Code:\s
                - Billing Country:\s
                - Industry:\s
                - Number Of Employees:\s
                """, tool.getContext(Map.of(), List.of(PROMPT), args(ACCOUNT_ID)).getFirst().document());
    }

    @Test
    void getContextAcceptsFifteenCharacterIds() {
        tool.getContext(Map.of(), List.of(PROMPT), args("001Qq00000a53mw"));

        assertEquals(List.of("mock_token:001Qq00000a53mw"), salesforceClient.accountRequests);
    }

    @Test
    void getContextTrimsTheAccountId() {
        tool.getContext(Map.of(), List.of(PROMPT), args(" " + ACCOUNT_ID + " "));

        assertEquals(List.of("mock_token:" + ACCOUNT_ID), salesforceClient.accountRequests);
    }

    @Test
    void getContextReturnsNothingWhenThereIsNoAccount() {
        assertEquals(List.of(), tool.getContext(Map.of(), List.of(PROMPT), args(ACCOUNT_ID)));
        assertEquals(List.of("mock_token:" + ACCOUNT_ID), salesforceClient.accountRequests);
    }

    @Test
    void getContextReadsTheAccountIdFromTheContextBeforeTheArgument() {
        tool.getContext(Map.of(SalesforceAccountDetails.ACCOUNT_ID_ARG, "001Qq00000a53mwAAA"), List.of(PROMPT), args(ACCOUNT_ID));

        assertEquals(List.of("mock_token:001Qq00000a53mwAAA"), salesforceClient.accountRequests);
    }

    @Test
    void getContextPrefersTheConfiguredAccountId() {
        TestConfigUtil.registerConfig(Map.of("sb.salesforceaccountdetails.accountid", "001Qq00000a53mwBBB"));

        tool.getContext(Map.of(SalesforceAccountDetails.ACCOUNT_ID_ARG, "001Qq00000a53mwAAA"), List.of(PROMPT), args(ACCOUNT_ID));

        assertEquals(List.of("mock_token:001Qq00000a53mwBBB"), salesforceClient.accountRequests);
    }

    @Test
    void getContextReturnsNothingWithoutAnAccountId() {
        assertEquals(List.of(), tool.getContext(Map.of(), List.of(PROMPT), List.of()));
        assertEquals(List.of(), tool.getContext(Map.of(), List.of(PROMPT), args(" ")));
        assertTrue(salesforceClient.tokenRequests.isEmpty());
        assertTrue(salesforceClient.accountRequests.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"001", "001Qq00000a53mwIA", "001Qq00000a53mwIAAA", "001Qq00000a53m'IAA", "' OR Id!=''"})
    void getContextRejectsInvalidAccountIds(final String accountId) {
        assertEquals(List.of(), tool.getContext(Map.of(), List.of(PROMPT), args(accountId)));
        assertTrue(salesforceClient.accountRequests.isEmpty());
    }

    @Test
    void getContextReturnsNothingWhenTheTokenCanNotBeRetrieved() {
        salesforceClient.failToken = true;

        assertEquals(List.of(), tool.getContext(Map.of(), List.of(PROMPT), args(ACCOUNT_ID)));
        assertTrue(salesforceClient.accountRequests.isEmpty());
    }

    @Test
    void getContextReturnsNothingWhenTheClientFails() {
        salesforceClient.failAccount = true;

        assertEquals(List.of(), tool.getContext(Map.of(), List.of(PROMPT), args(ACCOUNT_ID)));
        assertEquals(List.of("mock_token:" + ACCOUNT_ID), salesforceClient.accountRequests);
    }

    @Test
    void callAnswersFromTheAccountDetails() {
        salesforceClient.result = Optional.of(account());
        final Map<String, String> env = Map.of("key", "value");

        final RagMultiDocumentContext<Void> result = tool.call(env, List.of(PROMPT), args(ACCOUNT_ID));

        assertEquals("answer", result.getResponse());
        final RecordingLlmClient.Request request = llmClient.requests.getFirst();
        assertEquals(TOOL, request.tool());
        assertEquals(env, request.environmentSettings());
        assertEquals(List.of(PROMPT), request.document().getPrompts());
        assertTrue(Objects.requireNonNull(request.document().instructions()).contains("You are given the Salesforce account details"));
        assertEquals(List.of(EXPECTED_DOCUMENT), request.document().getIndividualContexts().stream().map(RagDocumentContext::document).toList());
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

        final InternalFailure ex = assertThrows(InternalFailure.class, () -> tool.call(Map.of(), List.of(PROMPT), args(ACCOUNT_ID)));

        assertInstanceOf(IllegalStateException.class, ex.getCause());
    }

    @Test
    void contextHashCodeCombinesArgumentsAndPrompts() {
        assertEquals(31 * 17 + List.of(PROMPT).hashCode(), tool.contextHashCode(Map.of(), List.of(PROMPT), args(ACCOUNT_ID)));
    }

    /**
     * Records the token and account requests and returns the configured result.
     */
    private static final class FakeSalesforceClient implements SalesforceClient {
        final List<String> tokenRequests = new CopyOnWriteArrayList<>();
        final List<String> accountRequests = new CopyOnWriteArrayList<>();
        Optional<SalesforceAccountRecord> result = Optional.empty();
        boolean failToken;
        boolean failAccount;

        @Override
        public SalesforceOauthTokenResponse getToken(final String clientId, final String clientSecret) {
            tokenRequests.add(clientId + ":" + clientSecret);
            if (failToken) {
                throw new IllegalStateException("Token unavailable");
            }
            return new SalesforceOauthTokenResponse("mock_token", "https://mock.salesforce.com", "mock_id", "Bearer", "0", "mock_signature");
        }

        @Override
        public Optional<SalesforceAccountRecord> getAccount(final String token, final String accountId) {
            accountRequests.add(token + ":" + accountId);
            if (failAccount) {
                throw new IllegalStateException("Salesforce unavailable");
            }
            return result;
        }

        @Override
        public boolean anyItemsInDuration(final String token, final String accountId, final ChronoUnit duration, final ChronoUnit cached) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SalesforceTaskRecord[] getTasks(final String token, final String accountId, final String type, final String startDate, final String endDate) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SalesforceEmailRecord[] getEmails(final String token, final String accountId, final String startDate, final String endDate) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SalesforceOpportunityQuery getOpportunityByAccountId(final String token, final String accountId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> getAccountAndOpportunityIds(final String token, final String accountId) {
            throw new UnsupportedOperationException();
        }
    }
}
