package secondbrain.domain.tools.publicfile;

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

import java.util.*;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(PublicWebConfig.class)
@AddBeanClasses(ArgsAccessorSimple.class)
@AddBeanClasses(ValidateStringBlank.class)
@AddBeanClasses(Keywords.class)
@AddBeanClasses(NamedHooksContainer.class)
@AddBeanClasses(GetFirstMarkdownBlock.class)
@AddBeanClasses(StandardExceptionMapping.class)
@AddBeanClasses(JsonDeserializerJackson.class)
@AddBeanClasses(Loggers.class)
class PublicWebConfigTest {
    private final RecordingLlmClient llmClient = new RecordingLlmClient();
    private final Map<String, String> settings = new HashMap<>();

    @Inject
    private PublicWebConfig config;

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
        return TestArgs.toStringGenerator("public-web-config", 47);
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

    private PublicWebConfig.LocalArguments local(final String prompt, final Map<String, String> context, final String... nameValues) {
        final List<ToolArgs> arguments = new ArrayList<>();
        for (int i = 0; i < nameValues.length; i += 2) {
            arguments.add(new ToolArgs(nameValues[i], nameValues[i + 1], true));
        }
        return config.new LocalArguments(arguments, List.of(prompt), context);
    }

    private PublicWebConfig.LocalArguments args(final String... nameValues) {
        return local("prompt", Map.of(), nameValues);
    }

    /**
     * Keywords caches generated keywords by prompt for the life of the JVM, so each test uses a unique prompt.
     */
    private static String uniquePrompt() {
        return "Find files about " + UUID.randomUUID();
    }

    /**
     * String settings that follow the config, context, argument, default precedence.
     */
    static Stream<Setting> stringSettings() {
        return Stream.of(
                new Setting(PublicFile.PUBLICWEB_URL_ARG, "sb.publicfile.url", PublicWebConfig.LocalArguments::getUrl),
                new Setting(CommonArguments.CONTENT_RATING_QUESTION_ARG, "sb.publicfile.contextFilterQuestion", PublicWebConfig.LocalArguments::getContextFilterQuestion),
                new Setting(CommonArguments.PREPROCESSOR_HOOKS_ARG, "sb.publicfile.preprocessorHooks", PublicWebConfig.LocalArguments::getPreprocessingHooks),
                new Setting(CommonArguments.PREINITIALIZATION_HOOKS_ARG, "sb.publicfile.preinitializationHooks", PublicWebConfig.LocalArguments::getPreinitializationHooks),
                new Setting(CommonArguments.POSTINFERENCE_HOOKS_ARG, "sb.publicfile.postinferenceHooks", PublicWebConfig.LocalArguments::getPostInferenceHooks));
    }

    record Setting(String name, String key, Function<PublicWebConfig.LocalArguments, String> getter) {
        @Override
        public String toString() {
            return name;
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stringSettings")
    void stringSettingsDefaultToEmpty(final Setting setting) {
        assertEquals("", setting.getter().apply(args()));
        assertEquals("", setting.getter().apply(args(setting.name(), " ")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stringSettings")
    void stringSettingsPrecedenceIsContextThenArgument(final Setting setting) {
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
    void keywordsAreSplitAndTrimmed() {
        assertEquals(List.of("alpha", "beta gamma", "delta"), args(CommonArguments.KEYWORDS_ARG, " alpha, beta gamma ,,delta ").getKeywords());
        assertEquals(List.of(), args().getKeywords());
        assertTrue(llmClient.requests.isEmpty());
    }

    @Test
    void keywordsAreReadFromTheContextBeforeTheArgument() {
        assertEquals(List.of("context"), local("prompt", Map.of(CommonArguments.KEYWORDS_ARG, "context"), CommonArguments.KEYWORDS_ARG, "argument").getKeywords());
    }

    @Test
    void configuredKeywordsWin() {
        configure("sb.publicfile.keywords", "configured,other");

        assertEquals(List.of("configured", "other"), local("prompt", Map.of(CommonArguments.KEYWORDS_ARG, "context"), CommonArguments.KEYWORDS_ARG, "argument").getKeywords());
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
    void generatedKeywordsAreUsedWhenNoKeywordsAreSupplied() {
        llmClient.respondWith(doc -> "[\"invoice\"]");

        assertEquals(List.of("invoice"), local(uniquePrompt(), Map.of(), CommonArguments.AUTO_GENERATE_KEYWORDS_ARG, "true").getKeywords());
    }

    @Test
    void generatedKeywordsAreEmptyWhenTheLlmResponseIsInvalid() {
        llmClient.respondWith(doc -> "not json");

        assertEquals(List.of("beta"), local(uniquePrompt(), Map.of(),
                CommonArguments.AUTO_GENERATE_KEYWORDS_ARG, "true",
                CommonArguments.KEYWORDS_ARG, "beta").getKeywords());
    }

    @ParameterizedTest
    @CsvSource({"true, true", "TRUE, true", "yes, true", "on, true", "false, false", "nonsense, false"})
    void autoGenerateKeywordsIsParsedAsABoolean(final String value, final boolean expected) {
        assertEquals(expected, args(CommonArguments.AUTO_GENERATE_KEYWORDS_ARG, value).getAutoGenerateKeywords());
    }

    @Test
    void autoGenerateKeywordsDefaultsToFalse() {
        assertFalse(args().getAutoGenerateKeywords());
    }

    @Test
    void autoGenerateKeywordsIsReadFromTheContextBeforeTheArgument() {
        assertTrue(local("prompt", Map.of(CommonArguments.AUTO_GENERATE_KEYWORDS_ARG, "true"), CommonArguments.AUTO_GENERATE_KEYWORDS_ARG, "false").getAutoGenerateKeywords());
    }

    @Test
    void autoGenerateKeywordsCanBeConfigured() {
        configure("sb.publicfile.autogeneratekeywords", "true");

        assertTrue(args(CommonArguments.AUTO_GENERATE_KEYWORDS_ARG, "false").getAutoGenerateKeywords());
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
    void keywordWindowIsReadFromTheContextBeforeTheArgument() {
        assertEquals(250, local("prompt", Map.of(CommonArguments.KEYWORD_WINDOW_ARG, "250"), CommonArguments.KEYWORD_WINDOW_ARG, "500").getKeywordWindow());
    }

    @Test
    void configuredKeywordWindowWins() {
        configure("sb.publicfile.keywordwindow", "750");

        assertEquals(750, args(CommonArguments.KEYWORD_WINDOW_ARG, "500").getKeywordWindow());
    }

    @Test
    void entityIsReadFromTheContextBeforeTheArgument() {
        assertEquals("", args().getEntity());
        assertEquals("Acme", args(CommonArguments.ENTITY_NAME_CONTEXT_ARG, "Acme").getEntity());
        assertEquals("Globex", local("prompt", Map.of(CommonArguments.ENTITY_NAME_CONTEXT_ARG, "Globex"), CommonArguments.ENTITY_NAME_CONTEXT_ARG, "Acme").getEntity());
    }

    @Test
    void toStringAndHashCodeDelegateToTheGenerator() {
        assertEquals("public-web-config", args().toString());
        assertEquals(47, args().hashCode());
    }
}
