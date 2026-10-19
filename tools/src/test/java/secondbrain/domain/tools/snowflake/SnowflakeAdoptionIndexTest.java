package secondbrain.domain.tools.snowflake;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(SnowflakeAdoptionIndex.class)
@AddBeanClasses(SnowflakeAdoptionIndexConfig.class)
@AddBeanClasses(ArgsAccessorSimple.class)
@AddBeanClasses(ValidateStringBlank.class)
@AddBeanClasses(StandardExceptionMapping.class)
class SnowflakeAdoptionIndexTest {
    private static final String PROMPT = "What is the adoption index?";
    private static final String TOOL = "SnowflakeAdoptionIndex";
    private static final String LABEL = "Snowflake Adoption Index";
    private static final String EXPECTED_DOCUMENT = """
            Snowflake Adoption Index for Account SFDC_acc-1:
            - Adoption Index: 72.5
            - Adoption Index Status: Healthy
            - Adoption Index Date: 2024-05-06
            """;

    private final FakeSnowflakeClient snowflakeClient = new FakeSnowflakeClient();
    private final RecordingLlmClient llmClient = new RecordingLlmClient();

    @Inject
    private SnowflakeAdoptionIndex tool;

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
        return TestArgs.toStringGenerator("snowflake-adoption-index", 17);
    }

    @BeforeEach
    void setUp() {
        TestConfigUtil.registerConfig(Map.of());
    }

    private static List<ToolArgs> args(final String companyId) {
        return List.of(new ToolArgs(SnowflakeAdoptionIndex.COMPANY_ID_ARG, companyId, true));
    }

    private static SnowflakeAdoptionIndexDetails details(final String accountId) {
        return new SnowflakeAdoptionIndexDetails(accountId, 72.5, "Healthy", LocalDate.of(2024, 5, 6));
    }

    @Test
    void describesItself() {
        assertEquals(TOOL, tool.getName());
        assertEquals(LABEL, tool.getContextLabel());
        assertEquals("Returns the Snowflake adoption index, adoption index status, and adoption index date for an account ID.", tool.getDescription());
        assertEquals(List.of(SnowflakeAdoptionIndex.COMPANY_ID_ARG), tool.getArguments().stream().map(ToolArguments::name).toList());
    }

    @Test
    void getContextConvertsTheAdoptionIndexToADocument() {
        snowflakeClient.result = Optional.of(details("SFDC_acc-1"));

        final List<RagDocumentContext<Void>> context = tool.getContext(Map.of(), List.of(PROMPT), args("acc-1"));

        assertEquals(1, context.size());
        final RagDocumentContext<Void> document = context.getFirst();
        assertEquals(EXPECTED_DOCUMENT, document.document());
        assertEquals("SFDC_acc-1", document.id());
        assertEquals(TOOL, document.tool());
        assertEquals(LABEL, document.contextLabel());
        assertNull(document.link());
        assertEquals(List.of(new IntermediateResult(EXPECTED_DOCUMENT, "Data-SnowflakeAdoptionIndex-SFDC_acc-1-SFDC_acc-1.txt")), document.getIntermediateResults());
        assertTrue(llmClient.requests.isEmpty());
    }

    @ParameterizedTest
    @CsvSource({
            "acc-1, SFDC_acc-1",
            "SFDC_acc-1, SFDC_acc-1",
            // The prefix check is case-sensitive
            "sfdc_acc-1, SFDC_sfdc_acc-1",
            "' acc-1', 'SFDC_ acc-1'"})
    void getContextPrefixesTheAccountId(final String supplied, final String expected) {
        tool.getContext(Map.of(), List.of(PROMPT), args(supplied));

        assertEquals(List.of(expected), snowflakeClient.ids);
    }

    @Test
    void getContextNamesTheIntermediateResultByRowAndRequestedId() {
        snowflakeClient.result = Optional.of(details("row-id"));

        final RagDocumentContext<Void> document = tool.getContext(Map.of(), List.of(PROMPT), args("requested")).getFirst();

        assertEquals("row-id", document.id());
        assertEquals("Data-SnowflakeAdoptionIndex-row-id-SFDC_requested.txt", document.getIntermediateResults().getFirst().filename());
    }

    @Test
    void getContextUsesDefaultsForMissingValues() {
        snowflakeClient.result = Optional.of(new SnowflakeAdoptionIndexDetails("SFDC_acc-2", null, null, null));

        assertEquals("""
                Snowflake Adoption Index for Account SFDC_acc-2:
                - Adoption Index: 0.0
                - Adoption Index Status:\s
                - Adoption Index Date: 1970-01-01
                """, tool.getContext(Map.of(), List.of(PROMPT), args("acc-2")).getFirst().document());
    }

    @Test
    void getContextReturnsNothingWhenThereIsNoAdoptionIndex() {
        assertEquals(List.of(), tool.getContext(Map.of(), List.of(PROMPT), args("acc-1")));
        assertEquals(List.of("SFDC_acc-1"), snowflakeClient.ids);
    }

    @Test
    void getContextReadsTheCompanyIdFromTheContextBeforeTheArgument() {
        tool.getContext(Map.of(SnowflakeAdoptionIndex.COMPANY_ID_ARG, "from-context"), List.of(PROMPT), args("from-argument"));

        assertEquals(List.of("SFDC_from-context"), snowflakeClient.ids);
    }

    @Test
    void getContextPrefersTheConfiguredCompanyId() {
        TestConfigUtil.registerConfig(Map.of("sb.snowflakeadoptionindex.companyId", "from-config"));

        tool.getContext(Map.of(SnowflakeAdoptionIndex.COMPANY_ID_ARG, "from-context"), List.of(PROMPT), args("from-argument"));

        assertEquals(List.of("SFDC_from-config"), snowflakeClient.ids);
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
        assertEquals(List.of("SFDC_acc-1"), snowflakeClient.ids);
    }

    @Test
    void getContextHandlesAnEmptyPromptList() {
        snowflakeClient.result = Optional.of(details("SFDC_acc-1"));

        assertEquals(1, tool.getContext(Map.of(), List.of(), args("acc-1")).size());
    }

    @Test
    void callAnswersFromTheAdoptionIndex() {
        snowflakeClient.result = Optional.of(details("SFDC_acc-1"));
        final Map<String, String> env = Map.of("key", "value");

        final RagMultiDocumentContext<Void> result = tool.call(env, List.of(PROMPT), args("acc-1"));

        assertEquals("answer", result.getResponse());
        final RecordingLlmClient.Request request = llmClient.requests.getFirst();
        assertEquals(TOOL, request.tool());
        assertEquals(env, request.environmentSettings());
        assertEquals(List.of(PROMPT), request.document().getPrompts());
        assertTrue(Objects.requireNonNull(request.document().instructions()).contains("You are given the Snowflake adoption index details"));
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

        final InternalFailure ex = assertThrows(InternalFailure.class, () -> tool.call(Map.of(), List.of(PROMPT), args("acc-1")));

        assertInstanceOf(IllegalStateException.class, ex.getCause());
    }

    @Test
    void contextHashCodeCombinesArgumentsAndPrompts() {
        assertEquals(31 * 17 + List.of(PROMPT).hashCode(), tool.contextHashCode(Map.of(), List.of(PROMPT), args("acc-1")));
    }

    /**
     * Records the IDs passed to getAdoptionIndex and returns the configured result.
     */
    private static final class FakeSnowflakeClient implements SnowflakeClient {
        final List<String> ids = new CopyOnWriteArrayList<>();
        Optional<SnowflakeAdoptionIndexDetails> result = Optional.empty();
        boolean fail;

        @Override
        public List<SnowflakeLicenseDetails> getLicenseDetails(final String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<SnowflakeAdoptionIndexDetails> getAdoptionIndex(final String id) {
            ids.add(id);
            if (fail) {
                throw new IllegalStateException("Snowflake unavailable");
            }
            return result;
        }
    }
}
