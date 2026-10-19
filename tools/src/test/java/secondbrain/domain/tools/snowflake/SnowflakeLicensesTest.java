package secondbrain.domain.tools.snowflake;

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
import secondbrain.domain.exceptionhandling.StandardExceptionMapping;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.objects.ToStringGenerator;
import secondbrain.domain.test.RecordingLlmClient;
import secondbrain.domain.test.TestArgs;
import secondbrain.domain.test.TestConfigUtil;
import secondbrain.domain.tooldefs.IntermediateResult;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.validate.ValidateStringBlank;
import secondbrain.infrastructure.llm.LlmClient;
import secondbrain.infrastructure.snowflake.SnowflakeAdoptionIndexDetails;
import secondbrain.infrastructure.snowflake.SnowflakeClient;
import secondbrain.infrastructure.snowflake.SnowflakeLicenseDetails;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(SnowflakeLicenses.class)
@AddBeanClasses(SnowflakeLicensesConfig.class)
@AddBeanClasses(ArgsAccessorSimple.class)
@AddBeanClasses(ValidateStringBlank.class)
@AddBeanClasses(StandardExceptionMapping.class)
class SnowflakeLicensesTest {
    private static final String PROMPT = "How many licenses are in use?";
    private static final String TOOL = "SnowflakeLicenses";

    private final FakeSnowflakeClient snowflakeClient = new FakeSnowflakeClient();
    private final RecordingLlmClient llmClient = new RecordingLlmClient();

    @Inject
    private SnowflakeLicenses tool;

    @Produces
    @Preferred
    SnowflakeClient produceSnowflakeClient() {
        return snowflakeClient;
    }

    @Produces
    @Preferred
    LlmClient produceLlmClient() {
        return llmClient;
    }

    @Produces
    ToStringGenerator produceToStringGenerator() {
        return TestArgs.toStringGenerator("snowflake-licenses", 13);
    }

    @BeforeEach
    void setUp() {
        TestConfigUtil.registerConfig(Map.of());
    }

    private static List<ToolArgs> args(final String companyId) {
        return List.of(new ToolArgs(SnowflakeLicenses.COMPANY_ID_ARG, companyId, true));
    }

    private static SnowflakeLicenseDetails details(final String id) {
        return new SnowflakeLicenseDetails(id, 50, 40, LocalDate.of(2024, 5, 6), 12, 10, 3, 4, 30, 20, 9, 8,
                7, 6, 5, 4, 25, 24, 2.5, 2.0, 100, 80);
    }

    private static final String EXPECTED_DOCUMENT = String.join("\n",
            "Snowflake License Details for Salesforce Account acc-1:",
            "- Total License Count: 50",
            "- Active License Count: 40",
            "- Last Recorded At: 2024-05-06",
            "- Projects: 12",
            "- Projects (30 days prior): 10",
            "- Projects (% change): 20%",
            "- Tenants: 3",
            "- Tenants (30 days prior): 4",
            "- Tenants (% change): -25%",
            "- Machines: 30",
            "- Machines (30 days prior): 20",
            "- Machines (% change): 50%",
            "- Monthly Active Users: 9",
            "- Monthly Active Users (30 days prior): 8",
            "- Monthly Active Users (% change): 12%",
            "- Active Projects: 7",
            "- Active Projects (30 days prior): 6",
            "- Active Projects (% change): 16%",
            "- Active Tenants: 5",
            "- Active Tenants (30 days prior): 4",
            "- Active Tenants (% change): 25%",
            "- Active Machines: 25",
            "- Active Machines (30 days prior): 24",
            "- Active Machines (% change): 4%",
            "- Deployments Per Day (current): 2.5",
            "- Deployments Per Day (30 days prior): 2.0",
            "- Deployments Per Day (% change): 25%",
            "");

    @Test
    void describesItself() {
        assertEquals(TOOL, tool.getName());
        assertEquals("Snowflake License Details", tool.getContextLabel());
        assertEquals("Returns Snowflake license and usage details for a Salesforce account ID.", tool.getDescription());
        assertEquals(List.of(SnowflakeLicenses.COMPANY_ID_ARG), tool.getArguments().stream().map(ToolArguments::name).toList());
    }

    @Test
    void getContextConvertsEachLicenseRowToADocument() {
        snowflakeClient.details.add(details("acc-1"));

        final List<RagDocumentContext<Void>> context = tool.getContext(Map.of(), List.of(PROMPT), args("acc-1"));

        assertEquals(1, context.size());
        final RagDocumentContext<Void> document = context.getFirst();
        // Licensed targets are available but are not included in the document
        assertEquals(EXPECTED_DOCUMENT, document.document());
        assertEquals("acc-1", document.id());
        assertEquals(TOOL, document.tool());
        assertEquals("Snowflake License Details", document.contextLabel());
        assertNull(document.link());
        assertEquals(List.of(new IntermediateResult(EXPECTED_DOCUMENT, "Data-Snowflake-acc-1-acc-1.txt")), document.getIntermediateResults());
        assertEquals(List.of("acc-1"), snowflakeClient.ids);
        assertTrue(llmClient.requests.isEmpty());
    }

    @Test
    void getContextNamesIntermediateResultsByRowAndRequestedId() {
        snowflakeClient.details.add(details("row-a"));
        snowflakeClient.details.add(details("row-b"));

        final List<RagDocumentContext<Void>> context = tool.getContext(Map.of(), List.of(PROMPT), args("requested"));

        assertEquals(List.of("row-a", "row-b"), context.stream().map(RagDocumentContext::id).toList());
        assertEquals(List.of("Data-Snowflake-row-a-requested.txt", "Data-Snowflake-row-b-requested.txt"),
                context.stream().map(c -> c.getIntermediateResults().getFirst().filename()).toList());
    }

    @Test
    void getContextUsesDefaultsForMissingValues() {
        snowflakeClient.details.add(new SnowflakeLicenseDetails("acc-2", null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null));

        final String document = tool.getContext(Map.of(), List.of(PROMPT), args("acc-2")).getFirst().document();

        assertTrue(document.contains("- Total License Count: 0\n"));
        assertTrue(document.contains("- Last Recorded At: 1970-01-01\n"));
        assertTrue(document.contains("- Projects (% change): 0%\n"));
        assertTrue(document.contains("- Deployments Per Day (current): 0.0\n"));
    }

    @Test
    void getContextReturnsNothingWhenNoRowsMatch() {
        assertEquals(List.of(), tool.getContext(Map.of(), List.of(PROMPT), args("acc-1")));
        assertEquals(List.of("acc-1"), snowflakeClient.ids);
    }

    @Test
    void getContextReadsTheCompanyIdFromTheContext() {
        snowflakeClient.details.add(details("acc-1"));

        tool.getContext(Map.of(SnowflakeLicenses.COMPANY_ID_ARG, "from-context"), List.of(PROMPT), args("from-argument"));

        assertEquals(List.of("from-context"), snowflakeClient.ids);
    }

    @Test
    void getContextPrefersTheConfiguredCompanyId() {
        TestConfigUtil.registerConfig(Map.of("sb.snowflake.companyId", "from-config"));

        tool.getContext(Map.of(SnowflakeLicenses.COMPANY_ID_ARG, "from-context"), List.of(PROMPT), args("from-argument"));

        assertEquals(List.of("from-config"), snowflakeClient.ids);
    }

    @Test
    void getContextReturnsNothingWithoutACompanyId() {
        assertEquals(List.of(), tool.getContext(Map.of(), List.of(PROMPT), List.of()));
        assertEquals(List.of(), tool.getContext(Map.of(), List.of(PROMPT), args(" ")));
        assertTrue(snowflakeClient.ids.isEmpty());
    }

    @Test
    void getContextReturnsNothingWhenTheClientFails() {
        snowflakeClient.fail = true;

        assertEquals(List.of(), tool.getContext(Map.of(), List.of(PROMPT), args("acc-1")));
        assertEquals(List.of("acc-1"), snowflakeClient.ids);
    }

    @Test
    void getContextHandlesAnEmptyPromptList() {
        snowflakeClient.details.add(details("acc-1"));

        assertEquals(1, tool.getContext(Map.of(), List.of(), args("acc-1")).size());
    }

    @Test
    void callAnswersFromTheLicenseDetails() {
        snowflakeClient.details.add(details("acc-1"));
        final Map<String, String> env = Map.of("key", "value");

        final RagMultiDocumentContext<Void> result = tool.call(env, List.of(PROMPT), args("acc-1"));

        assertEquals("answer", result.getResponse());
        final RecordingLlmClient.Request request = llmClient.requests.getFirst();
        assertEquals(TOOL, request.tool());
        assertEquals(env, request.environmentSettings());
        assertEquals(List.of(PROMPT), request.document().getPrompts());
        assertTrue(Objects.requireNonNull(request.document().instructions()).contains("You are given Snowflake license and usage details"));
        assertEquals(List.of(EXPECTED_DOCUMENT), request.document().getIndividualContexts().stream().map(RagDocumentContext::document).toList());
    }

    @Test
    void callStillAsksTheLlmWhenThereIsNoContext() {
        final RagMultiDocumentContext<Void> result = tool.call(Map.of(), List.of(PROMPT), List.of());

        assertEquals("answer", result.getResponse());
        assertEquals(List.of(), llmClient.requests.getFirst().document().getIndividualContexts());
    }

    @Test
    void callMapsLlmFailuresToInternalFailure() {
        llmClient.respondWith(doc -> {
            throw new IllegalStateException("LLM down");
        });

        final InternalFailure ex = assertThrows(InternalFailure.class, () -> tool.call(Map.of(), List.of(PROMPT), args("acc-1")));

        assertInstanceOf(IllegalStateException.class, ex.getCause());
    }

    @Test
    void contextHashCodeCombinesArgumentsAndPrompts() {
        assertEquals(31 * 13 + List.of(PROMPT).hashCode(), tool.contextHashCode(Map.of(), List.of(PROMPT), args("acc-1")));
    }

    /**
     * Records the IDs passed to getLicenseDetails and returns the configured rows.
     */
    private static final class FakeSnowflakeClient implements SnowflakeClient {
        final List<SnowflakeLicenseDetails> details = new ArrayList<>();
        final List<String> ids = new CopyOnWriteArrayList<>();
        boolean fail;

        @Override
        public List<SnowflakeLicenseDetails> getLicenseDetails(final String id) {
            ids.add(id);
            if (fail) {
                throw new IllegalStateException("Snowflake unavailable");
            }
            return details;
        }

        @Override
        public Optional<SnowflakeAdoptionIndexDetails> getAdoptionIndex(final String id) {
            throw new UnsupportedOperationException();
        }
    }
}
