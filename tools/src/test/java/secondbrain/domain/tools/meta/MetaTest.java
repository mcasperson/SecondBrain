package secondbrain.domain.tools.meta;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.apache.tika.utils.StringUtils;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import secondbrain.domain.answer.DefaultAnswerFormatterService;
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
import secondbrain.domain.httpclient.TimeoutTryHttpClientCalled;
import secondbrain.domain.httpclient.TryHttpClientCalled;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.json.JsonDeserializerJackson;
import secondbrain.domain.limit.DocumentTrimmerExactKeywords;
import secondbrain.domain.limit.ListLimiterAtomicCutOff;
import secondbrain.domain.list.TrimmedCommaSeparatedStringToList;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.mutex.MockMutex;
import secondbrain.domain.mutex.MockSemaphore;
import secondbrain.domain.mutex.Mutex;
import secondbrain.domain.mutex.Semaphore;
import secondbrain.domain.objects.SecretGetterGenerator;
import secondbrain.domain.persist.*;
import secondbrain.domain.processing.*;
import secondbrain.domain.response.OkResponseValidation;
import secondbrain.domain.sanitize.FinancialLocationContactRedaction;
import secondbrain.domain.sanitize.GetFirstDigits;
import secondbrain.domain.sanitize.GetFirstMarkdownBlock;
import secondbrain.domain.test.TestConfigUtil;
import secondbrain.domain.testconstants.TestConstants;
import secondbrain.domain.timeout.CompletableFutureTimeoutService;
import secondbrain.domain.tools.gong.Gong;
import secondbrain.domain.tools.keyword.Keywords;
import secondbrain.domain.tools.rating.RatingTool;
import secondbrain.domain.tools.salesforce.Salesforce;
import secondbrain.domain.validate.ValidateListEmptyOrNull;
import secondbrain.domain.validate.ValidateStringBlank;
import secondbrain.domain.web.ClientConstructorDefault;
import secondbrain.domain.zip.ApacheCommonsZStdZipper;
import secondbrain.infrastructure.azure.MessageTooLongResponseInspector;
import secondbrain.infrastructure.gong.GongClient;
import secondbrain.infrastructure.gong.GongClientMock;
import secondbrain.infrastructure.llm.LlmClient;
import secondbrain.infrastructure.mock.MockLLmCLient;
import secondbrain.infrastructure.salesforce.SalesforceClient;
import secondbrain.infrastructure.salesforce.SalesforceClientMock;
import secondbrain.infrastructure.salesforce.api.SalesforceEmailRecord;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tools.CommonArguments;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
// Meta tool and its config
@AddBeanClasses(Meta.class)
@AddBeanClasses(MetaConfig.class)
// Gong as a sub-tool for Meta to delegate to
@AddBeanClasses(Gong.class)
@AddBeanClasses(GongClientMock.class)
// Salesforce as a sub-tool for Meta to delegate to
@AddBeanClasses(Salesforce.class)
@AddBeanClasses(SalesforceClientMock.class)
// Infrastructure
@AddBeanClasses(MockLLmCLient.class)
@AddBeanClasses(Loggers.class)
@AddBeanClasses(ArgsAccessorSimple.class)
@AddBeanClasses(AesEncryptor.class)
@AddBeanClasses(ValidateStringBlank.class)
@AddBeanClasses(SecretGetterGenerator.class)
@AddBeanClasses(DateParserEverything.class)
@AddBeanClasses(DateParserIso8601.class)
@AddBeanClasses(DateParserUnix.class)
@AddBeanClasses(DateParserYyyyMmDd.class)
@AddBeanClasses(DateParserHawking.class)
@AddBeanClasses(StandardExceptionMapping.class)
@AddBeanClasses(NamedHooksContainer.class)
@AddBeanClasses(MockLocalStorage.class)
@AddBeanClasses(SharedVirtualThreadExecutor.class)
@AddBeanClasses(MockRatingMetadata.class)
@AddBeanClasses(RatingToolRatingFilter.class)
@AddBeanClasses(SentenceVectorizerDataToRagDoc.class)
@AddBeanClasses(LLMRagDocSummarizer.class)
@AddBeanClasses(RagDocSummarizerProducer.class)
@AddBeanClasses(SimpleSentenceSplitter.class)
@AddBeanClasses(JdlSentenceVectorizer.class)
@AddBeanClasses(DocumentTrimmerExactKeywords.class)
@AddBeanClasses(Keywords.class)
@AddBeanClasses(RatingTool.class)
@AddBeanClasses(JsonDeserializerJackson.class)
@AddBeanClasses(OkResponseValidation.class)
@AddBeanClasses(TryHttpClientCalled.class)
@AddBeanClasses(ClientConstructorDefault.class)
@AddBeanClasses(MockMutex.class)
@AddBeanClasses(DefaultAnswerFormatterService.class)
@AddBeanClasses(TimeoutTryHttpClientCalled.class)
@AddBeanClasses(GetFirstMarkdownBlock.class)
@AddBeanClasses(FinancialLocationContactRedaction.class)
@AddBeanClasses(LoggingExceptionHandler.class)
@AddBeanClasses(ValidateListEmptyOrNull.class)
@AddBeanClasses(MockLocalStorageReadWrite.class)
@AddBeanClasses(CompletableFutureTimeoutService.class)
@AddBeanClasses(ListLimiterAtomicCutOff.class)
@AddBeanClasses(GetFirstDigits.class)
@AddBeanClasses(ApacheCommonsZStdZipper.class)
@AddBeanClasses(TrimmedCommaSeparatedStringToList.class)
@AddBeanClasses(MessageTooLongResponseInspector.class)
@AddBeanClasses(MockSemaphore.class)
class MetaTest {

    @Inject
    private Meta meta;

    @Inject
    private MockLLmCLient mockLlmClient;

    @Inject
    private MockRatingMetadata mockRatingMetadata;

    @Inject
    private SalesforceClientMock salesforceClientMock;

    @Produces
    @Preferred
    @ApplicationScoped
    public LocalStorage produceLocalStorage() {
        return new MockLocalStorage();
    }

    @Produces
    @Preferred
    @ApplicationScoped
    public Mutex produceMutex() {
        return new MockMutex();
    }

    @Produces
    @Preferred
    @ApplicationScoped
    public Semaphore produceSemaphore() {
        return new MockSemaphore();
    }

    @Produces
    @Preferred
    @ApplicationScoped
    public GongClient produceGongClient(final GongClientMock gongClientMock) {
        return gongClientMock;
    }

    @Produces
    @Preferred
    @ApplicationScoped
    public SalesforceClient produceSalesforceClient(final SalesforceClientMock salesforceClientMock) {
        return salesforceClientMock;
    }

    @Produces
    @Preferred
    @ApplicationScoped
    public LocalStorageReadWrite produceLocalStorageReadWrite() {
        return new MockLocalStorageReadWrite();
    }

    @Produces
    @Preferred
    @ApplicationScoped
    public LlmClient produceLlmClient(final MockLLmCLient mockLLmCLient) {
        return mockLLmCLient;
    }

    @Produces
    @Preferred
    @ApplicationScoped
    public RatingMetadata produceRatingMetadata(final MockRatingMetadata mockRatingMetadata) {
        return mockRatingMetadata;
    }

    @BeforeAll
    static void registerConfig() {
        final String autodiscovery = System.getenv("SB_COSMOS_AUTODISCOVERY");
        final String gatewayMode = System.getenv("SB_COSMOS_GATEWAYMODE");

        final var configMap = new java.util.HashMap<String, String>();
        configMap.put("sb.cosmos.endpoint", TestConstants.COSMOS_EMULATOR_ENDPOINT);
        configMap.put("sb.cosmos.key", TestConstants.COSMOS_EMULATOR_KEY);
        configMap.put("sb.gong.accessKey", "testAccessKey");
        configMap.put("sb.gong.accessSecretKey", "testAccessSecretKey");
        configMap.put("sb.encryption.password", "testpassword");
        configMap.put("sb.encryption.salt", "testsalt1234");
        configMap.put("sb.meta.toolNames", "Gong,Salesforce");
        configMap.put("sb.cosmos.autodiscovery", StringUtils.isBlank(autodiscovery) ? "true" : autodiscovery);
        configMap.put("sb.cosmos.gatewayMode", StringUtils.isBlank(gatewayMode) ? "false" : gatewayMode);

        TestConfigUtil.registerConfig(configMap);
    }

    @BeforeEach
    void resetMocks() {
        mockLlmClient.setMockResponse(null);
        mockRatingMetadata.setMockRating(null);
        salesforceClientMock.resetMockEmails();
    }

    @Test
    void testGetContextEmptyTranscript() {
        final List<RagDocumentContext<Void>> result = meta.getContext(
                Map.of("gong_access_key", "testKey", "gong_access_secret_key", "testSecret"),
                List.of("What topics were discussed in recent calls?"),
                List.of());

        assertNotNull(result);
        assertTrue(result.isEmpty(), "Empty transcript from mock LLM should be filtered out");
    }

    @Test
    void testGetContextWithTranscript() {
        mockLlmClient.setMockResponse("This is a mock call transcript discussing AI product design.");

        final List<RagDocumentContext<Void>> result = meta.getContext(
                Map.of("gong_access_key", "testKey", "gong_access_secret_key", "testSecret"),
                List.of("What topics were discussed in recent calls?"),
                List.of());

        assertNotNull(result);
        assertFalse(result.isEmpty(), "Non-empty transcript should produce context results");
        assertEquals(1, result.size(), "Mock gong client returns exactly one call");
        assertNotNull(result.getFirst().document());
        assertFalse(result.getFirst().document().isBlank());
        assertTrue(result.getFirst().document().contains("mock call transcript"));
    }

    @Test
    void testGetContextWithHighRatingPassesFilter() {
        mockLlmClient.setMockResponse("This is a mock call transcript discussing AI product design.");
        mockRatingMetadata.setMockRating(8);

        final List<RagDocumentContext<Void>> result = meta.getContext(
                Map.of("gong_access_key", "testKey", "gong_access_secret_key", "testSecret"),
                List.of("What topics were discussed in recent calls?"),
                List.of(
                        new ToolArgs(CommonArguments.CONTENT_RATING_QUESTION_ARG, "Is this relevant?", true),
                        new ToolArgs(CommonArguments.CONTEXT_FILTER_MINIMUM_RATING_ARG, "5", true),
                        new ToolArgs(CommonArguments.FILTER_GREATER_THAN_ARG, "false", true)
                ));

        assertNotNull(result);
        assertFalse(result.isEmpty(), "Document with rating 8 should pass filter with minimum 5");
        assertEquals(1, result.size());
        assertNotNull(result.getFirst().getMetadata());
        assertTrue(result.getFirst().getMetadata().hasName("FilterRating"));
    }

    @Test
    void testGetContextWithLowRatingFilteredOut() {
        mockLlmClient.setMockResponse("This is a mock call transcript discussing AI product design.");
        mockRatingMetadata.setMockRating(2);

        final List<RagDocumentContext<Void>> result = meta.getContext(
                Map.of("gong_access_key", "testKey", "gong_access_secret_key", "testSecret"),
                List.of("What topics were discussed in recent calls?"),
                List.of(
                        new ToolArgs(CommonArguments.CONTENT_RATING_QUESTION_ARG, "Is this relevant?", true),
                        new ToolArgs(CommonArguments.CONTEXT_FILTER_MINIMUM_RATING_ARG, "5", true),
                        new ToolArgs(CommonArguments.FILTER_GREATER_THAN_ARG, "false", true)
                ));

        assertNotNull(result);
        assertTrue(result.isEmpty(), "Document with rating 2 should be filtered out with minimum 5");
    }

    @Test
    void testGetContextWithHighRatingFilteredOutUpperLimit() {
        mockLlmClient.setMockResponse("This is a mock call transcript discussing AI product design.");
        mockRatingMetadata.setMockRating(8);

        final List<RagDocumentContext<Void>> result = meta.getContext(
                Map.of("gong_access_key", "testKey", "gong_access_secret_key", "testSecret"),
                List.of("What topics were discussed in recent calls?"),
                List.of(
                        new ToolArgs(CommonArguments.CONTENT_RATING_QUESTION_ARG, "Is this relevant?", true),
                        new ToolArgs(CommonArguments.CONTEXT_FILTER_MINIMUM_RATING_ARG, "5", true),
                        new ToolArgs(CommonArguments.FILTER_GREATER_THAN_ARG, "true", true)
                ));

        assertNotNull(result);
        assertTrue(result.isEmpty(), "Document with rating 8 should be filtered out when upper limit is 5 and filterGreaterThan is true");
    }

    @Test
    void testGetContextWithLowRatingPassesUpperLimit() {
        mockLlmClient.setMockResponse("This is a mock call transcript discussing AI product design.");
        mockRatingMetadata.setMockRating(3);

        final List<RagDocumentContext<Void>> result = meta.getContext(
                Map.of("gong_access_key", "testKey", "gong_access_secret_key", "testSecret"),
                List.of("What topics were discussed in recent calls?"),
                List.of(
                        new ToolArgs(CommonArguments.CONTENT_RATING_QUESTION_ARG, "Is this relevant?", true),
                        new ToolArgs(CommonArguments.CONTEXT_FILTER_MINIMUM_RATING_ARG, "5", true),
                        new ToolArgs(CommonArguments.FILTER_GREATER_THAN_ARG, "true", true)
                ));

        assertNotNull(result);
        assertFalse(result.isEmpty(), "Document with rating 3 should pass when upper limit is 5 and filterGreaterThan is true");
        assertEquals(1, result.size());
    }

    @Test
    void testGetContextSalesforceNoEmails() {
        final List<RagDocumentContext<Void>> result = meta.getContext(
                Map.of("gong_access_key", "testKey", "gong_access_secret_key", "testSecret"),
                List.of("What emails were sent to the account?"),
                List.of(
                        new ToolArgs(Salesforce.CLIENT_ID, "mockClientId", true),
                        new ToolArgs(Salesforce.CLIENT_SECRET, "mockClientSecret", true),
                        new ToolArgs(Salesforce.ACCOUNT_ID, "001ABC123", true)
                ));

        assertNotNull(result);
        // With no mock emails set, Salesforce returns empty, and Gong returns empty (no mock LLM response)
        assertTrue(result.isEmpty(), "No emails configured in mock should produce empty Salesforce results");
    }

    @Test
    void testGetContextSalesforceWithEmails() {
        salesforceClientMock.setMockEmails(
                new SalesforceEmailRecord("email1", "Meeting Follow-up", "Hi, thanks for the meeting. We discussed the product roadmap.", "2026-06-20T10:00:00Z", "example.com"),
                new SalesforceEmailRecord("email2", "Action Items", "Here are the action items from our call.", "2026-06-21T14:00:00Z", "example.com")
        );

        final List<RagDocumentContext<Void>> result = meta.getContext(
                Map.of("gong_access_key", "testKey", "gong_access_secret_key", "testSecret"),
                List.of("What emails were sent to the account?"),
                List.of(
                        new ToolArgs(Salesforce.CLIENT_ID, "mockClientId", true),
                        new ToolArgs(Salesforce.CLIENT_SECRET, "mockClientSecret", true),
                        new ToolArgs(Salesforce.ACCOUNT_ID, "001ABC123", true)
                ));

        assertNotNull(result);
        // Gong returns empty (no mock LLM response), but Salesforce should return the 2 emails
        assertFalse(result.isEmpty(), "Mock emails should produce Salesforce context results");
        assertEquals(2, result.size(), "Two mock emails should produce two context results");
    }

    @Test
    void testGetContextSalesforceEmailContent() {
        salesforceClientMock.setMockEmails(
                new SalesforceEmailRecord("email1", "Product Discussion", "We need to finalize the pricing model for Q3.", "2026-06-20T10:00:00Z", "example.com")
        );

        final List<RagDocumentContext<Void>> result = meta.getContext(
                Map.of("gong_access_key", "testKey", "gong_access_secret_key", "testSecret"),
                List.of("What was discussed about pricing?"),
                List.of(
                        new ToolArgs(Salesforce.CLIENT_ID, "mockClientId", true),
                        new ToolArgs(Salesforce.CLIENT_SECRET, "mockClientSecret", true),
                        new ToolArgs(Salesforce.ACCOUNT_ID, "001ABC123", true)
                ));

        assertNotNull(result);
        assertFalse(result.isEmpty(), "Mock email should produce context result");
        assertEquals(1, result.size());
        assertTrue(result.getFirst().document().contains("pricing model"), "Context should contain the email body text");
    }
}
