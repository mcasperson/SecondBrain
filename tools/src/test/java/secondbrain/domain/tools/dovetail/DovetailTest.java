package secondbrain.domain.tools.dovetail;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import secondbrain.domain.args.ArgsAccessorSimple;
import secondbrain.domain.concurrency.SharedVirtualThreadExecutor;
import secondbrain.domain.context.JdlSentenceVectorizer;
import secondbrain.domain.context.SimpleSentenceSplitter;
import secondbrain.domain.date.DateParserEverything;
import secondbrain.domain.date.DateParserHawking;
import secondbrain.domain.date.DateParserIso8601;
import secondbrain.domain.date.DateParserUnix;
import secondbrain.domain.date.DateParserYyyyMmDd;
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
import secondbrain.domain.persist.CacheResult;
import secondbrain.domain.persist.GenerateValue;
import secondbrain.domain.processing.RatingToolRatingFilter;
import secondbrain.domain.processing.RatingToolRatingMetadata;
import secondbrain.domain.processing.SentenceVectorizerDataToRagDoc;
import secondbrain.domain.processing.LLMRagDocSummarizer;
import secondbrain.domain.tools.rating.RatingTool;
import secondbrain.domain.sanitize.FinancialLocationContactRedaction;
import secondbrain.domain.sanitize.GetFirstDigits;
import secondbrain.domain.sanitize.GetFirstMarkdownBlock;
import secondbrain.domain.validate.ValidateStringBlank;
import secondbrain.domain.validate.ValidateListEmptyOrNull;
import secondbrain.infrastructure.dovetail.DovetailClient;
import secondbrain.infrastructure.dovetail.DovetailClientMock;
import secondbrain.infrastructure.llm.LlmClient;
import secondbrain.infrastructure.mock.MockLLmCLient;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.tooldefs.ToolArgs;

import java.util.List;
import java.util.Map;
import java.util.Optional;

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
@AddBeanClasses(RatingToolRatingMetadata.class)
@AddBeanClasses(LLMRagDocSummarizer.class)
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
class DovetailTest {

    private static final String MOCK_LLM_RESPONSE = "Based on user research, the checkout process needs simplification.";

    @Inject
    private Dovetail dovetail;

    @Inject
    private MockLLmCLient mockLlmClient;

    @BeforeAll
    static void registerConfig() {
        final var configMap = new java.util.HashMap<String, String>();
        configMap.put("sb.infrastructure.mock", "true");
        configMap.put("sb.encryption.password", "testpassword");
        configMap.put("sb.encryption.salt", "testsalt1234");
        configMap.put("sb.dovetail.apiKey", "mock-api-key");
        configMap.put("sb.dovetail.baseUrl", "https://dovetail.com");

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

    @BeforeEach
    void setupMock() {
        mockLlmClient.setMockResponse(MOCK_LLM_RESPONSE);
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
        // Always cache miss — Dovetail will call the mocked DowetailClient instead.
        return new LocalStorage() {
            @Override
            public CacheResult<String> getString(String tool, String source, String promptHash) {
                // Always cache miss
                return new CacheResult<>(null, null, false);
            }

            @Override
            public CacheResult<String> getOrPutString(String tool, String source, String promptHash, long ttlSeconds, GenerateValue<String> generateValue) {
                return new CacheResult<>(generateValue.generate(), null, false);
            }

            @Override
            public CacheResult<String> getOrPutString(String tool, String source, String promptHash, GenerateValue<String> generateValue) {
                return getOrPutString(tool, source, promptHash, 0, generateValue);
            }

            @Override
            public <T> CacheResult<T> getOrPutObject(String tool, String source, String promptHash, long ttlSeconds, Class<T> clazz, GenerateValue<T> generateValue) {
                return new CacheResult<>(generateValue.generate(), null, false);
            }

            @Override
            public <T> CacheResult<T> getOrPutObject(String tool, String source, String promptHash, Class<T> clazz, GenerateValue<T> generateValue) {
                return getOrPutObject(tool, source, promptHash, 0, clazz, generateValue);
            }

            @Override
            public <T> CacheResult<List<T>> getOrPutList(String tool, String source, String promptHash, long ttlSeconds, Class<T> clazz, GenerateValue<List<T>> generateValue) {
                return new CacheResult<>(generateValue.generate(), null, false);
            }

            @Override
            public <T> CacheResult<List<T>> getOrPutList(String tool, String source, String promptHash, Class<T> clazz, GenerateValue<List<T>> generateValue) {
                return getOrPutList(tool, source, promptHash, 0, clazz, generateValue);
            }

            @Override
            public <T> CacheResult<T[]> getOrPutObjectArray(String tool, String source, String promptHash, long ttlSeconds, Class<T> clazz, Class<T[]> arrayClazz, GenerateValue<T[]> generateValue) {
                return new CacheResult<>(generateValue.generate(), null, false);
            }

            @Override
            public <T, U> CacheResult<T> getOrPutGeneric(String tool, String source, String promptHash, long ttlSeconds, Class<T> container, Class<U> contained, GenerateValue<T> generateValue) {
                return new CacheResult<>(generateValue.generate(), null, false);
            }

            @Override
            public <T, U> CacheResult<T> getOrPutGeneric(String tool, String source, String promptHash, Class<T> container, Class<U> contained, GenerateValue<T> generateValue) {
                return getOrPutGeneric(tool, source, promptHash, 0, container, contained, generateValue);
            }

            @Override
            public <T, U, V> CacheResult<T> getOrPutGeneric(String tool, String source, String promptHash, long ttlSeconds, Class<T> container, Class<U> contained, Class<V> contained2, GenerateValue<T> generateValue) {
                return new CacheResult<>(generateValue.generate(), null, false);
            }

            @Override
            public <T, U, V> CacheResult<T> getOrPutGeneric(String tool, String source, String promptHash, Class<T> container, Class<U> contained, Class<V> contained2, GenerateValue<T> generateValue) {
                return getOrPutGeneric(tool, source, promptHash, 0, container, contained, contained2, generateValue);
            }

            @Override
            public void putString(String tool, String source, String promptHash, long ttlSeconds, String response) {
            }

            @Override
            public void putString(String tool, String source, String promptHash, String response) {
            }

            @Override
            public void flush() {
            }

            @Override
            public <T> CacheResult<T[]> persistArrayResult(String tool, String source, String promptHash, long ttlSeconds, GenerateValue<T[]> generateValue) {
                return new CacheResult<>(generateValue.generate(), null, false);
            }
        };
    }

    @Produces
    @Preferred
    @ApplicationScoped
    public DovetailClient produceDovetailClient() {
        return new DovetailClientMock();
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
    }

    @Test
    void testGetNameConsistentWithClassName() {
        assertEquals(Dovetail.class.getSimpleName(), dovetail.getName());
    }

}
