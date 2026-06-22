package secondbrain.domain.tools.slackzengoogle;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.apache.tika.utils.StringUtils;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import secondbrain.domain.annotations.PropertyLabelReaderNullOnFailure;
import secondbrain.domain.answer.DefaultAnswerFormatterService;
import secondbrain.domain.args.ArgsAccessorSimple;
import secondbrain.domain.concurrency.SharedVirtualThreadExecutor;
import secondbrain.domain.context.JdlSentenceVectorizer;
import secondbrain.domain.context.SimpleSentenceSplitter;
import secondbrain.domain.converter.JSoupHtmlToText;
import secondbrain.domain.date.*;
import secondbrain.domain.debug.DebugToolArgsKeyValue;
import secondbrain.domain.encryption.AesEncryptor;
import secondbrain.domain.exceptionhandling.LoggingExceptionHandler;
import secondbrain.domain.exceptionhandling.StandardExceptionMapping;
import secondbrain.domain.hooks.NamedHooksContainer;
import secondbrain.domain.httpclient.TimeoutTryHttpClientCalled;
import secondbrain.domain.httpclient.TryHttpClientCalled;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.json.JsonDeserializerJackson;
import secondbrain.domain.keyword.RakeKeywordExtractor;
import secondbrain.domain.limit.DocumentTrimmerExactKeywords;
import secondbrain.domain.limit.ListLimiterAtomicCutOff;
import secondbrain.domain.list.TrimmedCommaSeparatedStringToList;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.mutex.MockMutex;
import secondbrain.domain.mutex.Mutex;
import secondbrain.domain.objects.SecretGetterGenerator;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.persist.LocalStorageReadWrite;
import secondbrain.domain.persist.MockLocalStorage;
import secondbrain.domain.persist.MockLocalStorageReadWrite;
import secondbrain.domain.processing.*;
import secondbrain.domain.reader.FileReaderSelector;
import secondbrain.domain.response.OkResponseValidation;
import secondbrain.domain.sanitize.*;
import secondbrain.domain.test.TestConfigUtil;
import secondbrain.domain.testconstants.TestConstants;
import secondbrain.domain.timeout.CompletableFutureTimeoutService;
import secondbrain.domain.tools.gong.Gong;
import secondbrain.domain.tools.googledocs.GoogleDocs;
import secondbrain.domain.tools.keyword.Keywords;
import secondbrain.domain.tools.planhat.PlanHat;
import secondbrain.domain.tools.planhat.PlanHatUsage;
import secondbrain.domain.tools.rating.RatingTool;
import secondbrain.domain.tools.salesforce.Salesforce;
import secondbrain.domain.tools.slack.SlackChannel;
import secondbrain.domain.tools.slack.SlackSearch;
import secondbrain.domain.tools.zendesk.SanitizeOrganization;
import secondbrain.domain.tools.zendesk.ZenDeskIndividualTicket;
import secondbrain.domain.tools.zendesk.ZenDeskOrganization;
import secondbrain.domain.validate.Llama32ValidateInputs;
import secondbrain.domain.validate.ValidateListEmptyOrNull;
import secondbrain.domain.validate.ValidateStringBlank;
import secondbrain.domain.web.ClientConstructorDefault;
import secondbrain.domain.yaml.YamlDeserializerJackson;
import secondbrain.domain.zip.ApacheCommonsZStdZipper;
import secondbrain.domain.zip.ApacheCompressZipper;
import secondbrain.infrastructure.azure.MessageTooLongResponseInspector;
import secondbrain.infrastructure.gong.GongClient;
import secondbrain.infrastructure.gong.GongClientMock;
import secondbrain.infrastructure.llm.LlmClient;
import secondbrain.infrastructure.mock.MockLLmCLient;
import secondbrain.infrastructure.planhat.PlanHatClient;
import secondbrain.infrastructure.planhat.PlanHatClientMock;
import secondbrain.infrastructure.salesforce.SalesforceClient;
import secondbrain.infrastructure.salesforce.SalesforceClientMock;
import secondbrain.infrastructure.slack.SlackClient;
import secondbrain.infrastructure.slack.SlackClientMock;
import secondbrain.infrastructure.zendesk.ZenDeskClient;
import secondbrain.infrastructure.zendesk.ZenDeskClientMock;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
// MultiSlackZenGoogle and its config
@AddBeanClasses(MultiSlackZenGoogle.class)
@AddBeanClasses(MultiSlackZenGoogleConfig.class)
// Sub-tools
@AddBeanClasses(SlackChannel.class)
@AddBeanClasses(SlackSearch.class)
@AddBeanClasses(ZenDeskOrganization.class)
@AddBeanClasses(GoogleDocs.class)
@AddBeanClasses(PlanHat.class)
@AddBeanClasses(PlanHatUsage.class)
@AddBeanClasses(Gong.class)
@AddBeanClasses(Salesforce.class)
@AddBeanClasses(RatingTool.class)
@AddBeanClasses(Keywords.class)
// Client implementations
@AddBeanClasses(SlackClientMock.class)
@AddBeanClasses(ZenDeskClientMock.class)
@AddBeanClasses(PlanHatClientMock.class)
@AddBeanClasses(GongClientMock.class)
@AddBeanClasses(SalesforceClientMock.class)
// ZenDesk support
@AddBeanClasses(ZenDeskIndividualTicket.class)
@AddBeanClasses(SanitizeOrganization.class)
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
@AddBeanClasses(MockRagDocSummarizer.class)
@AddBeanClasses(SimpleSentenceSplitter.class)
@AddBeanClasses(JdlSentenceVectorizer.class)
@AddBeanClasses(DocumentTrimmerExactKeywords.class)
@AddBeanClasses(JsonDeserializerJackson.class)
@AddBeanClasses(OkResponseValidation.class)
@AddBeanClasses(TryHttpClientCalled.class)
@AddBeanClasses(TimeoutTryHttpClientCalled.class)
@AddBeanClasses(ClientConstructorDefault.class)
@AddBeanClasses(DefaultAnswerFormatterService.class)
@AddBeanClasses(GetFirstMarkdownBlock.class)
@AddBeanClasses(FinancialLocationContactRedaction.class)
@AddBeanClasses(LoggingExceptionHandler.class)
@AddBeanClasses(ValidateListEmptyOrNull.class)
@AddBeanClasses(MockLocalStorageReadWrite.class)
@AddBeanClasses(CompletableFutureTimeoutService.class)
@AddBeanClasses(ListLimiterAtomicCutOff.class)
@AddBeanClasses(GetFirstDigits.class)
@AddBeanClasses(ApacheCommonsZStdZipper.class)
@AddBeanClasses(ApacheCompressZipper.class)
@AddBeanClasses(TrimmedCommaSeparatedStringToList.class)
@AddBeanClasses(MessageTooLongResponseInspector.class)
@AddBeanClasses(YamlDeserializerJackson.class)
@AddBeanClasses(FileReaderSelector.class)
@AddBeanClasses(DebugToolArgsKeyValue.class)
@AddBeanClasses(JSoupHtmlToText.class)
@AddBeanClasses(RakeKeywordExtractor.class)
@AddBeanClasses(PropertyLabelReaderNullOnFailure.class)
@AddBeanClasses(RemoveSpacing.class)
@AddBeanClasses(SanitizeEmail.class)
@AddBeanClasses(Llama32ValidateInputs.class)
class MultiSlackZenGoogleTest {

    @Inject
    private MultiSlackZenGoogle multiSlackZenGoogle;

    @Inject
    private MockLLmCLient mockLlmClient;

    @Inject
    private MockRagDocSummarizer mockRagDocSummarizer;

    @Inject
    private MockRatingMetadata mockRatingMetadata;

    @Produces
    @Preferred
    @ApplicationScoped
    public LlmClient produceLlmClient() {
        return mockLlmClient;
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
    public SlackClient produceSlackClient() {
        return new SlackClientMock();
    }

    @Produces
    @Preferred
    @ApplicationScoped
    public ZenDeskClient produceZenDeskClient() {
        return new ZenDeskClientMock();
    }

    @Produces
    @Preferred
    @ApplicationScoped
    public PlanHatClient producePlanHatClient() {
        return new PlanHatClientMock();
    }

    @Produces
    @Preferred
    @ApplicationScoped
    public GongClient produceGongClient() {
        return new GongClientMock();
    }

    @Produces
    @Preferred
    @ApplicationScoped
    public SalesforceClient produceSalesforceClient() {
        return new SalesforceClientMock();
    }

    @Produces
    @Preferred
    @ApplicationScoped
    public LocalStorageReadWrite produceLocalStorageReadWrite() {
        return new MockLocalStorageReadWrite();
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
        configMap.put("sb.cosmos.autodiscovery", StringUtils.isBlank(autodiscovery) ? "true" : autodiscovery);
        configMap.put("sb.cosmos.gatewayMode", StringUtils.isBlank(gatewayMode) ? "false" : gatewayMode);

        TestConfigUtil.registerConfig(configMap);
    }

    @Test
    void testGetContext() {
        // The tool requires a URL to an entity directory YAML file.
        // Without a valid URL, it throws an exception, which confirms CDI wiring is complete.
        assertThrows(Exception.class, () -> multiSlackZenGoogle.getContext(
                Map.of("gong_access_key", "testKey", "gong_access_secret_key", "testSecret"),
                List.of("What topics were discussed recently?"),
                List.of()));
    }
}












