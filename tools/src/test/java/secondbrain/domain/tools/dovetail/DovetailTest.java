package secondbrain.domain.tools.dovetail;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import secondbrain.domain.args.ArgsAccessorSimple;
import secondbrain.domain.concurrency.SharedVirtualThreadExecutor;
import secondbrain.domain.context.JdlSentenceVectorizer;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.SimpleSentenceSplitter;
import secondbrain.domain.date.*;
import secondbrain.domain.encryption.AesEncryptor;
import secondbrain.domain.exceptionhandling.LoggingExceptionHandler;
import secondbrain.domain.exceptionhandling.StandardExceptionMapping;
import secondbrain.domain.hooks.NamedHooksContainer;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.json.JsonDeserializerJackson;
import secondbrain.domain.limit.DocumentTrimmerExactKeywords;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.objects.SecretGetterGenerator;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.persist.MockLocalStorage;
import secondbrain.domain.processing.MockRagDocSummarizer;
import secondbrain.domain.processing.RagDocSummarizer;
import secondbrain.domain.processing.RatingToolRatingFilter;
import secondbrain.domain.processing.MockRatingMetadata;
import secondbrain.domain.processing.RatingMetadata;
import secondbrain.domain.processing.SentenceVectorizerDataToRagDoc;
import secondbrain.domain.sanitize.FinancialLocationContactRedaction;
import secondbrain.domain.test.TestConfigUtil;
import secondbrain.domain.sanitize.GetFirstDigits;
import secondbrain.domain.sanitize.GetFirstMarkdownBlock;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.tools.rating.RatingTool;
import secondbrain.domain.validate.ValidateListEmptyOrNull;
import secondbrain.domain.validate.ValidateStringBlank;
import secondbrain.infrastructure.dovetail.DovetailClient;
import secondbrain.infrastructure.dovetail.DovetailClientMock;
import secondbrain.infrastructure.llm.LlmClient;
import secondbrain.infrastructure.mock.MockLLmCLient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Dovetail} using Weld CDI to validate the full context resolution
 * path with mocked external clients.
 */
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
// Core tool and config
@AddBeanClasses(Dovetail.class)
@AddBeanClasses(DovetailConfig.class)
// Arguments/config resolution
@AddBeanClasses(ArgsAccessorSimple.class)
@AddBeanClasses(SecretGetterGenerator.class)
@AddBeanClasses(AesEncryptor.class)
@AddBeanClasses(ValidateStringBlank.class)
@AddBeanClasses(Loggers.class)
// Infrastructure (used by Keywords, Sanitize transitive deps)
@AddBeanClasses(FinancialLocationContactRedaction.class)
@AddBeanClasses(GetFirstMarkdownBlock.class)
@AddBeanClasses(JsonDeserializerJackson.class)
// Date parsing (transitive dependency of DovetailConfig via @Identifier("everything"))
@AddBeanClasses(DateParserEverything.class)
@AddBeanClasses(DateParserIso8601.class)
@AddBeanClasses(DateParserUnix.class)
@AddBeanClasses(DateParserYyyyMmDd.class)
@AddBeanClasses(DateParserHawking.class)
// Data processing infrastructure
@AddBeanClasses(SentenceVectorizerDataToRagDoc.class)
@AddBeanClasses(DocumentTrimmerExactKeywords.class)
@AddBeanClasses(JdlSentenceVectorizer.class)
@AddBeanClasses(SimpleSentenceSplitter.class)
@AddBeanClasses(RatingToolRatingFilter.class)
@AddBeanClasses(MockRatingMetadata.class)
@AddBeanClasses(MockRagDocSummarizer.class)
@AddBeanClasses(RatingTool.class)
@AddBeanClasses(GetFirstDigits.class)
@AddBeanClasses(ValidateListEmptyOrNull.class)
// Hooks & exception mapping
@AddBeanClasses(NamedHooksContainer.class)
@AddBeanClasses(StandardExceptionMapping.class)
@AddBeanClasses(LoggingExceptionHandler.class)
// Concurrency
@AddBeanClasses(SharedVirtualThreadExecutor.class)
// LLM and storage — mocked via @Produces methods below
@AddBeanClasses(MockLLmCLient.class)
@AddBeanClasses(DovetailClientMock.class)
@AddBeanClasses(MockLocalStorage.class)
class DovetailTest {

    private static final String MOCK_LLM_RESPONSE = "Based on user research, the checkout process needs simplification.";

    @Inject
    private Dovetail dovetail;

    @Inject
    private MockLLmCLient mockLlmClient;

    @Inject
    private MockRagDocSummarizer mockRagDocSummarizer;

    @Inject
    private MockRatingMetadata mockRatingMetadata;

    @BeforeAll
    static void registerConfig() {
        final var configMap = new java.util.HashMap<String, String>();
        configMap.put("sb.infrastructure.mock", "true");
        configMap.put("sb.encryption.password", "testpassword");
        configMap.put("sb.encryption.salt", "testsalt1234");
        configMap.put("sb.dovetail.apiKey", "mock-api-key");
        configMap.put("sb.dovetail.baseUrl", "https://dovetail.com");

        TestConfigUtil.registerConfig(configMap);
    }

    @BeforeEach
    void setupMock() {
        mockLlmClient.setMockResponse(MOCK_LLM_RESPONSE);
        mockRagDocSummarizer.setMockSummary("Mock summarized item content");
    }

    @Produces
    @Preferred
    @ApplicationScoped
    public LlmClient produceLlmClient() {
        return mockLlmClient;
    }

    @Produces
    @Preferred
    @ApplicationScoped
    public LocalStorage produceLocalStorage() {
        return new MockLocalStorage();
    }

    @Produces
    @Preferred
    @ApplicationScoped
    public DovetailClient produceDovetailClient() {
        return new DovetailClientMock();
    }

    @Produces
    @Preferred
    @ApplicationScoped
    public RagDocSummarizer produceRagDocSummarizer() {
        return mockRagDocSummarizer;
    }

    @Produces
    @Preferred
    @ApplicationScoped
    public RatingMetadata produceRatingMetadata() {
        return mockRatingMetadata;
    }

    // ─── Basic property checks ───

    @Test
    void testNameReturnsCorrectValue() {
        assertEquals("Dovetail", dovetail.getName());
    }

    @Test
    void testGetDescriptionReturnsNonEmptyString() {
        final String description = dovetail.getDescription();
        assertNotNull(description);
        assertTrue(description.contains("user research"));
    }

    @Test
    void testGetNameIsNotBlank() {
        final String name = dovetail.getName();
        assertNotNull(name);
        assertFalse(name.isBlank());
    }

    @Test
    void testGetArgumentsIsNonEmpty() {
        final List<ToolArguments> arguments = dovetail.getArguments();
        assertNotNull(arguments);
        assertFalse(arguments.isEmpty());
    }

    @Test
    void testGetContextLabelReturnsExpectedLabel() {
        assertEquals("Dovetail data item", dovetail.getContextLabel());
    }

    // ─── Weld-resolved context tests ───

    @Test
    void testGetContextWithApiKeyReturnsNonNullContext() {
        final Map<String, String> settings = Map.of("dovetail_api_key", "mock-api-key-value");
        final List<String> prompts = List.of("What do users say about checkout?");
        final List<ToolArgs> arguments = List.of();

        final List<RagDocumentContext<Void>> context = dovetail.getContext(settings, prompts, arguments);
        assertNotNull(context);
        assertFalse(context.isEmpty());

        final var item = context.getFirst();
        assertEquals("Dovetail", item.tool());
        assertEquals(DovetailClientMock.MOCK_DATA_ITEM.id(), item.id());
        assertTrue(item.document().contains("Mock Dovetail Export"));
    }

    @Test
    void testGetContextWithSummarizationUsesMockSummary() {
        final Map<String, String> settings = Map.of("dovetail_api_key", "mock-api-key-value");
        final List<String> prompts = List.of("What do users say about checkout?");
        final List<ToolArgs> arguments = List.of(
                new ToolArgs("summarizeDocument", "true", true),
                new ToolArgs("summarizeDocumentPrompt", "Summarize this item", true)
        );

        final List<RagDocumentContext<Void>> context = dovetail.getContext(settings, prompts, arguments);
        assertNotNull(context);
        assertFalse(context.isEmpty());

        final var item = context.getFirst();
        assertEquals("Dovetail", item.tool());
        assertEquals(DovetailClientMock.MOCK_DATA_ITEM.id(), item.id());
        assertEquals("Mock summarized item content", item.document());
    }

    @Test
    void testGetNameConsistentWithClassName() {
        assertEquals(Dovetail.class.getSimpleName(), dovetail.getName());
    }

}
