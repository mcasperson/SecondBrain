package secondbrain.domain.tools.slackzengoogle;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.config.inject.ConfigExtension;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import secondbrain.domain.args.ArgsAccessorSimple;
import secondbrain.domain.annotations.PropertyLabelReaderNullOnFailure;
import secondbrain.domain.answer.DefaultAnswerFormatterService;
import secondbrain.domain.concurrency.SharedVirtualThreadExecutor;
import secondbrain.domain.config.MockConfig;
import secondbrain.domain.context.JdlSentenceVectorizer;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.SimpleSentenceSplitter;
import secondbrain.domain.converter.JSoupHtmlToText;
import secondbrain.domain.date.DateParserEverything;
import secondbrain.domain.date.DateParserHawking;
import secondbrain.domain.date.DateParserIso8601;
import secondbrain.domain.date.DateParserUnix;
import secondbrain.domain.date.DateParserYyyyMmDd;
import secondbrain.domain.debug.DebugToolArgsKeyValue;
import secondbrain.domain.encryption.AesEncryptor;
import secondbrain.domain.exceptionhandling.LoggingExceptionHandler;
import secondbrain.domain.exceptionhandling.StandardExceptionMapping;
import secondbrain.domain.hooks.NamedHooksContainer;
import secondbrain.domain.httpclient.TimeoutTryHttpClientCalled;
import secondbrain.domain.httpclient.TryHttpClientCalled;
import secondbrain.domain.json.JsonDeserializerJackson;
import secondbrain.domain.keyword.RakeKeywordExtractor;
import secondbrain.domain.limit.DocumentTrimmerExactKeywords;
import secondbrain.domain.limit.ListLimiterAtomicCutOff;
import secondbrain.domain.list.TrimmedCommaSeparatedStringToList;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.mutex.CosmosMutex;
import secondbrain.domain.mutex.FileLockMutex;
import secondbrain.domain.mutex.MutexProducer;
import secondbrain.domain.objects.SecretGetterGenerator;
import secondbrain.domain.persist.CosmosLocalStorage;
import secondbrain.domain.persist.FileLocalStorageReadWrite;
import secondbrain.domain.persist.H2LocalStorage;
import secondbrain.domain.persist.LocalStorageProducer;
import secondbrain.domain.persist.LocalStorageReadWriteProducer;
import secondbrain.domain.persist.MockLocalStorageReadWrite;
import secondbrain.domain.processing.LLMRagDocSummarizer;
import secondbrain.domain.processing.RatingToolRatingFilter;
import secondbrain.domain.processing.RatingToolRatingMetadata;
import secondbrain.domain.processing.SentenceVectorizerDataToRagDoc;
import secondbrain.domain.reader.FileReaderSelector;
import secondbrain.domain.response.OkResponseValidation;
import secondbrain.domain.sanitize.FinancialLocationContactRedaction;
import secondbrain.domain.sanitize.GetFirstDigits;
import secondbrain.domain.sanitize.GetFirstMarkdownBlock;
import secondbrain.domain.sanitize.RemoveSpacing;
import secondbrain.domain.sanitize.SanitizeEmail;
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
import secondbrain.infrastructure.LlmClientProducer;
import secondbrain.infrastructure.azure.AzureClient;
import secondbrain.infrastructure.azure.MessageTooLongResponseInspector;
import secondbrain.infrastructure.gong.GongClientLive;
import secondbrain.infrastructure.gong.GongClientMock;
import secondbrain.infrastructure.gong.GongClientProducer;
import secondbrain.infrastructure.google.GoogleClient;
import secondbrain.infrastructure.mock.MockLLmCLient;
import secondbrain.infrastructure.ollama.OllamaClient;
import secondbrain.infrastructure.planhat.PlanHatClientLive;
import secondbrain.infrastructure.planhat.PlanHatClientMock;
import secondbrain.infrastructure.planhat.PlanHatClientProducer;
import secondbrain.infrastructure.salesforce.SalesforceClientLive;
import secondbrain.infrastructure.slack.SlackClientLive;
import secondbrain.infrastructure.slack.SlackClientMock;
import secondbrain.infrastructure.slack.SlackClientProducer;
import secondbrain.infrastructure.zendesk.ZenDeskClientLive;
import secondbrain.infrastructure.zendesk.ZenDeskClientMock;
import secondbrain.infrastructure.zendesk.ZenDeskClientProducer;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
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
// Client producers and implementations
@AddBeanClasses(SlackClientProducer.class)
@AddBeanClasses(SlackClientLive.class)
@AddBeanClasses(SlackClientMock.class)
@AddBeanClasses(ZenDeskClientProducer.class)
@AddBeanClasses(ZenDeskClientLive.class)
@AddBeanClasses(ZenDeskClientMock.class)
@AddBeanClasses(PlanHatClientProducer.class)
@AddBeanClasses(PlanHatClientLive.class)
@AddBeanClasses(PlanHatClientMock.class)
@AddBeanClasses(GongClientProducer.class)
@AddBeanClasses(GongClientLive.class)
@AddBeanClasses(GongClientMock.class)
@AddBeanClasses(SalesforceClientLive.class)
// ZenDesk support
@AddBeanClasses(ZenDeskIndividualTicket.class)
@AddBeanClasses(SanitizeOrganization.class)
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
@AddBeanClasses(LocalStorageProducer.class)
@AddBeanClasses(H2LocalStorage.class)
@AddBeanClasses(CosmosLocalStorage.class)
@AddBeanClasses(SharedVirtualThreadExecutor.class)
@AddBeanClasses(RatingToolRatingMetadata.class)
@AddBeanClasses(RatingToolRatingFilter.class)
@AddBeanClasses(SentenceVectorizerDataToRagDoc.class)
@AddBeanClasses(LLMRagDocSummarizer.class)
@AddBeanClasses(SimpleSentenceSplitter.class)
@AddBeanClasses(JdlSentenceVectorizer.class)
@AddBeanClasses(DocumentTrimmerExactKeywords.class)
@AddBeanClasses(JsonDeserializerJackson.class)
@AddBeanClasses(OkResponseValidation.class)
@AddBeanClasses(TryHttpClientCalled.class)
@AddBeanClasses(TimeoutTryHttpClientCalled.class)
@AddBeanClasses(ClientConstructorDefault.class)
@AddBeanClasses(MutexProducer.class)
@AddBeanClasses(FileLockMutex.class)
@AddBeanClasses(CosmosMutex.class)
@AddBeanClasses(DefaultAnswerFormatterService.class)
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

    @BeforeEach
    void updateConfig() {
        final var configSource = new PropertiesConfigSource(
                Map.of(
                        "sb.infrastructure.mock", "true",
                        "sb.llm.client", "ollama",
                        "sb.ollama.url", "http://localhost:11434",
                        "sb.gong.accessKey", "testAccessKey",
                        "sb.gong.accessSecretKey", "testAccessSecretKey",
                        "sb.localstorage.provider", "h2",
                        "sb.mutex.provider", "file",
                        "sb.encryption.password", "testpassword",
                        "sb.encryption.salt", "testsalt1234"
                ),
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
        // The tool requires a URL to an entity directory YAML file.
        // Without a valid URL, it throws an exception, which confirms CDI wiring is complete.
        assertThrows(Exception.class, () -> multiSlackZenGoogle.getContext(
                Map.of("gong_access_key", "testKey", "gong_access_secret_key", "testSecret"),
                List.of("What topics were discussed recently?"),
                List.of()));
    }
}












