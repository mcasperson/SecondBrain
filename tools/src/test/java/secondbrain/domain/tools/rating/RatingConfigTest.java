package secondbrain.domain.tools.rating;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import secondbrain.domain.args.ArgsAccessorSimple;
import secondbrain.domain.objects.ToStringGenerator;
import secondbrain.domain.test.TestArgs;
import secondbrain.domain.test.TestConfigUtil;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tools.CommonArguments;
import secondbrain.domain.validate.ValidateStringBlank;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(RatingConfig.class)
@AddBeanClasses(ArgsAccessorSimple.class)
@AddBeanClasses(ValidateStringBlank.class)
class RatingConfigTest {
    @Inject
    private RatingConfig config;

    @Produces
    ToStringGenerator produceToStringGenerator() {
        return TestArgs.toStringGenerator("rating-config", 19);
    }

    @BeforeEach
    void setUp() {
        TestConfigUtil.registerConfig(Map.of());
    }

    private RatingConfig.LocalArguments local(final Map<String, String> context, final String... nameValues) {
        final List<ToolArgs> arguments = new java.util.ArrayList<>();
        for (int i = 0; i < nameValues.length; i += 2) {
            arguments.add(new ToolArgs(nameValues[i], nameValues[i + 1], true));
        }
        return config.new LocalArguments(arguments, List.of("prompt"), context);
    }

    /**
     * Each setting with its argument name, configuration key and getter.
     */
    static Stream<Setting> settings() {
        return Stream.of(
                setting(RatingTool.RATING_FIRST_MODEL_ARG, "sb.rating.firstModel", RatingConfig.LocalArguments::getFirstModel),
                setting(RatingTool.RATING_FIRST_CONTEXT_WINDOW_ARG, "sb.rating.firstContextWindow", RatingConfig.LocalArguments::getFirstContextWindow),
                setting(RatingTool.RATING_FIRST_REASONING_EFFORT_ARG, "sb.rating.firstReasoningEffort", RatingConfig.LocalArguments::getFirstReasoningEffort),
                setting(RatingTool.RATING_FIRST_URL_ARG, "sb.rating.firstUrl", RatingConfig.LocalArguments::getFirstUrl),
                setting(RatingTool.RATING_SECOND_MODEL_ARG, "sb.rating.secondModel", RatingConfig.LocalArguments::getSecondModel),
                setting(RatingTool.RATING_SECOND_CONTEXT_WINDOW_ARG, "sb.rating.secondContextWindow", RatingConfig.LocalArguments::getSecondContextWindow),
                setting(RatingTool.RATING_SECOND_REASONING_EFFORT_ARG, "sb.rating.secondReasoningEffort", RatingConfig.LocalArguments::getSecondReasoningEffort),
                setting(RatingTool.RATING_SECOND_URL_ARG, "sb.rating.secondUrl", RatingConfig.LocalArguments::getSecondUrl),
                setting(RatingTool.RATING_THIRD_MODEL_ARG, "sb.rating.thirdModel", RatingConfig.LocalArguments::getThirdModel),
                setting(RatingTool.RATING_THIRD_CONTEXT_WINDOW_ARG, "sb.rating.thirdContextWindow", RatingConfig.LocalArguments::getThirdContextWindow),
                setting(RatingTool.RATING_THIRD_REASONING_EFFORT_ARG, "sb.rating.thirdReasoningEffort", RatingConfig.LocalArguments::getThirdReasoningEffort),
                setting(RatingTool.RATING_THIRD_URL_ARG, "sb.rating.thirdUrl", RatingConfig.LocalArguments::getThirdUrl),
                setting(CommonArguments.PREPROCESSOR_HOOKS_ARG, "sb.rating.preprocessorHooks", RatingConfig.LocalArguments::getPreprocessingHooks),
                setting(CommonArguments.PREINITIALIZATION_HOOKS_ARG, "sb.rating.preinitializationHooks", RatingConfig.LocalArguments::getPreinitializationHooks),
                setting(CommonArguments.POSTINFERENCE_HOOKS_ARG, "sb.rating.postinferenceHooks", RatingConfig.LocalArguments::getPostInferenceHooks));
    }

    private static Setting setting(final String name, final String key, final Function<RatingConfig.LocalArguments, String> getter) {
        return new Setting(name, key, getter);
    }

    record Setting(String name, String key, Function<RatingConfig.LocalArguments, String> getter) {
        @Override
        public String toString() {
            return name;
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("settings")
    void settingsDefaultToBlank(final Setting setting) {
        assertEquals("", setting.getter().apply(local(Map.of())));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("settings")
    void settingsAreReadFromTheArguments(final Setting setting) {
        assertEquals("from-argument", setting.getter().apply(local(Map.of(), setting.name(), "from-argument")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("settings")
    void blankArgumentsFallBackToTheDefault(final Setting setting) {
        assertEquals("", setting.getter().apply(local(Map.of(), setting.name(), " ")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("settings")
    void contextValuesWinOverArguments(final Setting setting) {
        assertEquals("from-context", setting.getter().apply(local(Map.of(setting.name(), "from-context"), setting.name(), "from-argument")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("settings")
    void configuredValuesWinOverContextAndArguments(final Setting setting) {
        TestConfigUtil.registerConfig(Map.of(setting.key(), "from-config"));

        assertEquals("from-config", setting.getter().apply(local(Map.of(setting.name(), "from-context"), setting.name(), "from-argument")));
    }

    @Test
    void documentToolAndIdAreOnlyReadFromTheContext() {
        final Map<String, String> context = Map.of(
                RatingTool.RATING_DOCUMENT_CONTEXT_ARG, "the document",
                RatingTool.RATING_TOOL_CONTEXT_ARG, "SlackChannel",
                RatingTool.RATING_ID_CONTEXT_ARG, "id-7");

        final RatingConfig.LocalArguments local = local(context);

        assertEquals("the document", local.getDocument());
        assertEquals("SlackChannel", local.getRatingTool());
        assertEquals("id-7", local.getRatingId());
    }

    @Test
    void documentToolAndIdIgnoreArguments() {
        final RatingConfig.LocalArguments local = local(Map.of(),
                RatingTool.RATING_DOCUMENT_CONTEXT_ARG, "the document",
                RatingTool.RATING_TOOL_CONTEXT_ARG, "SlackChannel",
                RatingTool.RATING_ID_CONTEXT_ARG, "id-7");

        assertEquals("", local.getDocument());
        assertEquals("", local.getRatingTool());
        assertEquals("", local.getRatingId());
    }

    @Test
    void ignoreInvalidResponsesDefaultsToTrue() {
        // Without a configured value the accessor default of "true" is used
        assertTrue(local(Map.of()).ignoreInvalidResponses());
    }

    @Test
    void ignoreInvalidResponsesIsReadFromTheContextBeforeTheArgument() {
        assertFalse(local(Map.of(), RatingTool.IGNORE_INVALID_RESPONSES_ARG, "false").ignoreInvalidResponses());
        assertTrue(local(Map.of(RatingTool.IGNORE_INVALID_RESPONSES_ARG, "true"), RatingTool.IGNORE_INVALID_RESPONSES_ARG, "false").ignoreInvalidResponses());
        assertFalse(local(Map.of(RatingTool.IGNORE_INVALID_RESPONSES_ARG, "false"), RatingTool.IGNORE_INVALID_RESPONSES_ARG, "true").ignoreInvalidResponses());
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "TRUE", "yes", "on", "1"})
    void ignoreInvalidResponsesCanBeEnabledInTheConfiguration(final String value) {
        TestConfigUtil.registerConfig(Map.of("sb.rating.ignoreInvalidResponses", value));

        assertTrue(local(Map.of(), RatingTool.IGNORE_INVALID_RESPONSES_ARG, "false").ignoreInvalidResponses());
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "no", "0", "maybe"})
    void ignoreInvalidResponsesTreatsOtherConfiguredValuesAsFalse(final String value) {
        TestConfigUtil.registerConfig(Map.of("sb.rating.ignoreInvalidResponses", value));

        assertFalse(local(Map.of()).ignoreInvalidResponses());
    }

    @Test
    void toStringAndHashCodeDelegateToTheGenerator() {
        assertEquals("rating-config", local(Map.of()).toString());
        assertEquals(19, local(Map.of()).hashCode());
    }
}
