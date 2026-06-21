package secondbrain.domain.tools.meta;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.apache.tika.utils.StringUtils;
import secondbrain.domain.injection.Preferred;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import secondbrain.domain.answer.DefaultAnswerFormatterService;
import secondbrain.domain.args.ArgsAccessorSimple;
import secondbrain.domain.concurrency.SharedVirtualThreadExecutor;
import secondbrain.domain.config.MockConfig;
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
import secondbrain.domain.processing.LLMRagDocSummarizer;
import secondbrain.domain.processing.RagDocSummarizerProducer;
import secondbrain.domain.processing.RatingToolRatingFilter;
import secondbrain.domain.processing.RatingToolRatingMetadata;
import secondbrain.domain.processing.SentenceVectorizerDataToRagDoc;
import secondbrain.domain.response.OkResponseValidation;
import secondbrain.domain.sanitize.FinancialLocationContactRedaction;
import secondbrain.domain.sanitize.GetFirstDigits;
import secondbrain.domain.sanitize.GetFirstMarkdownBlock;
import secondbrain.domain.testconstants.TestConstants;
import secondbrain.domain.timeout.CompletableFutureTimeoutService;
import secondbrain.domain.tools.gong.Gong;
import secondbrain.domain.tools.keyword.Keywords;
import secondbrain.domain.tools.rating.RatingTool;
import secondbrain.domain.validate.ValidateListEmptyOrNull;
import secondbrain.domain.validate.ValidateStringBlank;
import secondbrain.domain.web.ClientConstructorDefault;
import secondbrain.domain.zip.ApacheCommonsZStdZipper;
import secondbrain.infrastructure.LlmClientProducer;
import secondbrain.infrastructure.azure.AzureClient;
import secondbrain.infrastructure.azure.MessageTooLongResponseInspector;
import secondbrain.infrastructure.gong.GongClientLive;
import secondbrain.infrastructure.gong.GongClientMock;
import secondbrain.infrastructure.gong.GongClientProducer;
import secondbrain.infrastructure.google.GoogleClient;
import secondbrain.infrastructure.mock.MockLLmCLient;
import secondbrain.infrastructure.ollama.OllamaClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
// Meta tool and its config
@AddBeanClasses(Meta.class)
@AddBeanClasses(MetaConfig.class)
// Gong as a sub-tool for Meta to delegate to
@AddBeanClasses(Gong.class)
@AddBeanClasses(GongClientProducer.class)
@AddBeanClasses(GongClientMock.class)
@AddBeanClasses(GongClientLive.class)
// Infrastructure
@AddBeanClasses(MockConfig.class)
@AddBeanClasses(LlmClientProducer.class)
@AddBeanClasses(OllamaClient.class)
@AddBeanClasses(AzureClient.class)
@AddBeanClasses(GoogleClient.class)
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
@AddBeanClasses(RatingToolRatingMetadata.class)
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
@AddBeanClasses(FileLocalStorageReadWrite.class)
@AddBeanClasses(LocalStorageReadWriteProducer.class)
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

    @BeforeAll
    static void registerConfig() {
        final String autodiscovery = System.getenv("SB_COSMOS_AUTODISCOVERY");
        final String gatewayMode = System.getenv("SB_COSMOS_GATEWAYMODE");

        final var configMap = new java.util.HashMap<String, String>();
        configMap.put("sb.cosmos.endpoint", TestConstants.COSMOS_EMULATOR_ENDPOINT);
        configMap.put("sb.cosmos.key", TestConstants.COSMOS_EMULATOR_KEY);
        configMap.put("sb.infrastructure.mock", "true");
        configMap.put("sb.gong.accessKey", "testAccessKey");
        configMap.put("sb.gong.accessSecretKey", "testAccessSecretKey");
        configMap.put("sb.localstorage.provider", "h2");
        configMap.put("sb.mutex.provider", "file");
        configMap.put("sb.encryption.password", "testpassword");
        configMap.put("sb.encryption.salt", "testsalt1234");
        configMap.put("sb.meta.toolNames", "Gong");
        configMap.put("sb.cosmos.autodiscovery", StringUtils.isBlank(autodiscovery) ? "true" : autodiscovery);
        configMap.put("sb.cosmos.gatewayMode", StringUtils.isBlank(gatewayMode) ? "false" : gatewayMode);

        final var configSource = new PropertiesConfigSource(
                configMap,
                "TestConfig",
                Integer.MAX_VALUE
        );
        final Config newConfig = new SmallRyeConfigBuilder()
                .withSources(configSource)
                .build();

        final var configProviderResolver = ConfigProviderResolver.instance();
        final var oldConfig = configProviderResolver.getConfig();
        configProviderResolver.releaseConfig(oldConfig);
        configProviderResolver.registerConfig(
                newConfig,
                Thread.currentThread().getContextClassLoader()
        );
    }

    @Test
    void testGetContext() {
        final List<RagDocumentContext<Void>> result = meta.getContext(
                Map.of("gong_access_key", "testKey", "gong_access_secret_key", "testSecret"),
                List.of("What topics were discussed in recent calls?"),
                List.of());

        assertNotNull(result);
    }
}



