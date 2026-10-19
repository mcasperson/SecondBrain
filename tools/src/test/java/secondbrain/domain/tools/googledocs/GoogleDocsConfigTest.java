package secondbrain.domain.tools.googledocs;

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
import org.junit.jupiter.params.provider.MethodSource;
import secondbrain.domain.args.ArgsAccessorSimple;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.exceptionhandling.StandardExceptionMapping;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.hooks.NamedHooksContainer;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.json.JsonDeserializerJackson;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.objects.ToStringGenerator;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.sanitize.GetFirstMarkdownBlock;
import secondbrain.domain.test.RecordingLlmClient;
import secondbrain.domain.test.RecordingLocalStorage;
import secondbrain.domain.test.TestArgs;
import secondbrain.domain.test.TestConfigUtil;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tools.CommonArguments;
import secondbrain.domain.tools.keyword.Keywords;
import secondbrain.domain.validate.ValidateStringBlank;
import secondbrain.infrastructure.llm.LlmClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(GoogleDocsConfig.class)
@AddBeanClasses(ArgsAccessorSimple.class)
@AddBeanClasses(ValidateStringBlank.class)
@AddBeanClasses(Keywords.class)
@AddBeanClasses(NamedHooksContainer.class)
@AddBeanClasses(GetFirstMarkdownBlock.class)
@AddBeanClasses(StandardExceptionMapping.class)
@AddBeanClasses(JsonDeserializerJackson.class)
@AddBeanClasses(Loggers.class)
class GoogleDocsConfigTest {
    private final RecordingLlmClient llmClient = new RecordingLlmClient();
    private final Map<String, String> settings = new HashMap<>();

    @Inject
    private GoogleDocsConfig config;

    @Produces
    @Preferred
    LlmClient produceLlmClient() {
        return llmClient;
    }

    @Produces
    @Preferred
    LocalStorage produceLocalStorage() {
        return new RecordingLocalStorage();
    }

    @Produces
    ToStringGenerator produceToStringGenerator() {
        return TestArgs.toStringGenerator("google-docs-config", 43);
    }

    @BeforeEach
    void setUp() {
        TestConfigUtil.registerConfig(settings);
    }

    /**
     * Registers a configuration value. Must be called before the config bean is first used, as CDI creates it lazily.
     */
    private void configure(final String key, final String value) {
        settings.put(key, value);
        TestConfigUtil.registerConfig(settings);
    }

    private GoogleDocsConfig.LocalArguments local(final String prompt, final Map<String, String> context, final String... nameValues) {
        final List<ToolArgs> arguments = new ArrayList<>();
        for (int i = 0; i < nameValues.length; i += 2) {
            arguments.add(new ToolArgs(nameValues[i], nameValues[i + 1], true));
        }
        return config.new LocalArguments(arguments, List.of(prompt), context);
    }

    private GoogleDocsConfig.LocalArguments args(final String... nameValues) {
        return local("prompt", Map.of(), nameValues);
    }

    /**
     * Keywords caches generated keywords by prompt for the life of the JVM, so each test uses a unique prompt.
     */
    private static String uniquePrompt() {
        return "Find documents about " + UUID.randomUUID();
    }

    /**
     * String settings that follow the config, context, argument, default precedence.
     */
    static Stream<Setting> stringSettings() {
        return Stream.of(
                new Setting(CommonArguments.SUMMARIZE_DOCUMENT_PROMPT_ARG, "sb.google.summarizedocumentprompt", "Summarise the document in three paragraphs", GoogleDocsConfig.LocalArguments::getDocumentSummaryPrompt),
                new Setting(CommonArguments.CONTENT_RATING_QUESTION_ARG, "sb.google.contextFilterQuestion", "", GoogleDocsConfig.LocalArguments::getContextFilterQuestion),
                new Setting(CommonArguments.PREPROCESSOR_HOOKS_ARG, "sb.google.preprocessorHooks", "", GoogleDocsConfig.LocalArguments::getPreprocessingHooks),
                new Setting(CommonArguments.PREINITIALIZATION_HOOKS_ARG, "sb.google.preinitializationHooks", "", GoogleDocsConfig.LocalArguments::getPreinitializationHooks),
                new Setting(CommonArguments.POSTINFERENCE_HOOKS_ARG, "sb.google.postinferenceHooks", "", GoogleDocsConfig.LocalArguments::getPostInferenceHooks));
    }

    record Setting(String name, String key, String defaultValue,
                   Function<GoogleDocsConfig.LocalArguments, String> getter) {
        @Override
        public String toString() {
            return name;
        }
    }


    @ParameterizedTest(name = "{0}")
    @MethodSource("stringSettings")
    void stringSettingsHaveDefaults(final Setting setting) {
        assertEquals(setting.defaultValue(), setting.getter().apply(args()));
        assertEquals(setting.defaultValue(), setting.getter().apply(args(setting.name(), " ")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stringSettings")
    void stringSettingsPrecedenceIsConfigThenContextThenArgument(final Setting setting) {
        assertEquals("from-argument", setting.getter().apply(args(setting.name(), "from-argument")));
        assertEquals("from-context", setting.getter().apply(local("prompt", Map.of(setting.name(), "from-context"), setting.name(), "from-argument")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stringSettings")
    void stringSettingsPreferTheConfiguration(final Setting setting) {
        configure(setting.key(), "from-config");

        assertEquals("from-config", setting.getter().apply(local("prompt", Map.of(setting.name(), "from-context"), setting.name(), "from-argument")));
    }

    @Test
    void documentIdPrecedenceIsConfigThenContextThenArgument() {
        assertEquals("doc-arg", args(GoogleDocs.GOOGLE_DOC_ID_ARG, "doc-arg").getDocumentId());
        assertEquals("doc-context", local("prompt", Map.of(GoogleDocs.GOOGLE_DOC_ID_ARG, "doc-context"), GoogleDocs.GOOGLE_DOC_ID_ARG, "doc-arg").getDocumentId());
    }

    @Test
    void configuredDocumentIdWins() {
        configure("sb.google.doc", "doc-config");

        assertEquals("doc-config", local("prompt", Map.of(GoogleDocs.GOOGLE_DOC_ID_ARG, "doc-context"), GoogleDocs.GOOGLE_DOC_ID_ARG, "doc-arg").getDocumentId());
    }

    @Test
    void documentIdIsRequired() {
        final InternalFailure missing = assertThrows(InternalFailure.class, () -> args().getDocumentId());
        final InternalFailure blank = assertThrows(InternalFailure.class, () -> args(GoogleDocs.GOOGLE_DOC_ID_ARG, "  ").getDocumentId());

        assertEquals("Google document ID is required", missing.getMessage());
        assertEquals("Google document ID is required", blank.getMessage());
    }

    @Test
    void keywordsAreSplitAndTrimmed() {
        assertEquals(List.of("alpha", "beta gamma", "delta"), args(CommonArguments.KEYWORDS_ARG, " alpha, beta gamma ,,delta ").getKeywords());
        assertEquals(List.of(), args().getKeywords());
        assertTrue(llmClient.requests.isEmpty());
    }

    @Test
    void configuredKeywordsWin() {
        configure("sb.google.keywords", "configured");

        assertEquals(List.of("configured"), local("prompt", Map.of(CommonArguments.KEYWORDS_ARG, "context"), CommonArguments.KEYWORDS_ARG, "argument").getKeywords());
    }

    @Test
    void generatedKeywordsAreAppendedToSuppliedKeywordsWithoutDuplicates() {
        llmClient.respondWith(doc -> "[\"gamma\", \"alpha\"]");
        final String prompt = uniquePrompt();

        final List<String> keywords = local(prompt, Map.of(),
                CommonArguments.AUTO_GENERATE_KEYWORDS_ARG, "true",
                CommonArguments.KEYWORDS_ARG, "beta,alpha",
                CommonArguments.CONTENT_RATING_QUESTION_ARG, "Is it about billing?").getKeywords();

        // Supplied keywords keep their order, followed by new generated keywords, with the duplicate "alpha" removed
        assertEquals(List.of("beta", "alpha", "gamma"), keywords);
        final RecordingLlmClient.Request request = llmClient.requests.getFirst();
        assertEquals("Keywords", request.tool());
        // The prompts and the context filter question are sent to the keyword generator
        assertEquals(List.of(prompt + "\nIs it about billing?"), request.document().getPrompts());
    }

    @Test
    void generatedKeywordsAreEmptyWhenTheLlmResponseIsInvalid() {
        llmClient.respondWith(doc -> "not json");

        assertEquals(List.of("beta"), local(uniquePrompt(), Map.of(),
                CommonArguments.AUTO_GENERATE_KEYWORDS_ARG, "true",
                CommonArguments.KEYWORDS_ARG, "beta").getKeywords());
    }

    @ParameterizedTest
    @CsvSource({"true, true", "TRUE, true", "yes, true", "false, false", "nonsense, false"})
    void autoGenerateKeywordsIsParsedAsABoolean(final String value, final boolean expected) {
        assertEquals(expected, args(CommonArguments.AUTO_GENERATE_KEYWORDS_ARG, value).getAutoGenerateKeywords());
    }

    @Test
    void autoGenerateKeywordsDefaultsToFalse() {
        assertFalse(args().getAutoGenerateKeywords());
    }

    @Test
    void autoGenerateKeywordsCanBeConfigured() {
        configure("sb.google.generatekeywords", "true");
        assertTrue(args(CommonArguments.AUTO_GENERATE_KEYWORDS_ARG, "false").getAutoGenerateKeywords());
    }

    @Test
    void serviceAccountJsonFailsWhenNotConfigured() {
        assertThrows(NoSuchElementException.class, () -> args().getGoogleServiceAccountJson());
    }

    @Test
    void serviceAccountJsonComesFromTheConfiguration() {
        configure("sb.google.serviceaccountjson", "{\"type\":\"service_account\"}");
        assertEquals("{\"type\":\"service_account\"}", args().getGoogleServiceAccountJson());
    }

    @Test
    void entityIsReadFromTheContextBeforeTheArgument() {
        assertEquals("", args().getEntity());
        assertEquals("Acme", args(CommonArguments.ENTITY_NAME_CONTEXT_ARG, "Acme").getEntity());
        assertEquals("Globex", local("prompt", Map.of(CommonArguments.ENTITY_NAME_CONTEXT_ARG, "Globex"), CommonArguments.ENTITY_NAME_CONTEXT_ARG, "Acme").getEntity());
    }

    @ParameterizedTest
    @CsvSource({"500, 500", "0, 0", "-3, -3", "wide, " + Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH})
    void keywordWindowIsParsed(final String value, final int expected) {
        assertEquals(expected, args(CommonArguments.KEYWORD_WINDOW_ARG, value).getKeywordWindow());
    }

    @Test
    void keywordWindowHasADefault() {
        assertEquals(Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH, args().getKeywordWindow());
    }

    @Test
    void configuredKeywordWindowWins() {
        configure("sb.google.keywordwindow", "750");
        assertEquals(750, args(CommonArguments.KEYWORD_WINDOW_ARG, "500").getKeywordWindow());
    }

    @Test
    void summarizeDocumentDefaultsToFalse() {
        assertFalse(args().getSummarizeDocument());
    }

    @Test
    void summarizeDocumentIsReadFromTheContextBeforeTheArgument() {
        assertTrue(args(CommonArguments.SUMMARIZE_DOCUMENT_ARG, "true").getSummarizeDocument());
        assertTrue(local("prompt", Map.of(CommonArguments.SUMMARIZE_DOCUMENT_ARG, "true"), CommonArguments.SUMMARIZE_DOCUMENT_ARG, "false").getSummarizeDocument());
        assertFalse(local("prompt", Map.of(CommonArguments.SUMMARIZE_DOCUMENT_ARG, "false"), CommonArguments.SUMMARIZE_DOCUMENT_ARG, "true").getSummarizeDocument());
    }

    @Test
    void configuredSummarizeDocumentWins() {
        configure("sb.google.summarizedocument", "true");

        assertTrue(local("prompt", Map.of(CommonArguments.SUMMARIZE_DOCUMENT_ARG, "false"), CommonArguments.SUMMARIZE_DOCUMENT_ARG, "false").getSummarizeDocument());
    }

    @ParameterizedTest
    @CsvSource({"7, 7", "0, 0", "-4, 0", "high, 10"})
    void defaultRatingIsParsedAndClampedAtZero(final String value, final int expected) {
        assertEquals(expected, args(CommonArguments.DEFAULT_RATING_ARG, value).getDefaultRating());
    }

    @Test
    void defaultRatingDefaultsToTen() {
        assertEquals(10, args().getDefaultRating());
    }

    @Test
    void configuredDefaultRatingWins() {
        configure("sb.google.contextFilterDefaultRating", "3");
        assertEquals(3, args(CommonArguments.DEFAULT_RATING_ARG, "7").getDefaultRating());
    }

    @ParameterizedTest
    @CsvSource({"6, 6", "-2, -2", "lots, 0"})
    void contextFilterMinimumRatingIsParsed(final String value, final int expected) {
        assertEquals(expected, args(CommonArguments.CONTEXT_FILTER_MINIMUM_RATING_ARG, value).getContextFilterMinimumRating());
    }

    @Test
    void contextFilterMinimumRatingDefaultsToZero() {
        assertEquals(0, args().getContextFilterMinimumRating());
    }

    @Test
    void configuredContextFilterMinimumRatingWins() {
        configure("sb.google.contextFilterMinimumRating", "8");
        assertEquals(8, args(CommonArguments.CONTEXT_FILTER_MINIMUM_RATING_ARG, "6").getContextFilterMinimumRating());
    }

    @Test
    void toStringAndHashCodeDelegateToTheGenerator() {
        assertEquals("google-docs-config", args().toString());
        assertEquals(43, args().hashCode());
    }
}
