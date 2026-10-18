package secondbrain.domain.tools.slack;

import io.smallrye.common.annotation.Identifier;
import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.jboss.weld.junit5.auto.ExcludeBeanClasses;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import secondbrain.domain.args.ArgsAccessorSimple;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.encryption.Encryptor;
import secondbrain.domain.objects.ToStringGenerator;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.test.TestArgs;
import secondbrain.domain.test.TestConfigUtil;
import secondbrain.domain.validate.ValidateStringBlank;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tools.CommonArguments;
import secondbrain.domain.tools.keyword.Keywords;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(SlackChannelConfig.class)
@AddBeanClasses(ArgsAccessorSimple.class)
@AddBeanClasses(ValidateStringBlank.class)
// Keywords is replaced by a mock from a producer method
@ExcludeBeanClasses(Keywords.class)
class SlackChannelConfigTest {
    private final Keywords keywordsTool = mock(Keywords.class);

    @Inject
    private SlackChannelConfig config;

    @Produces
    Keywords produceKeywords() {
        return keywordsTool;
    }

    @Produces
    @Identifier("AES")
    Encryptor produceEncryptor() {
        return TestArgs.encryptor();
    }

    @Produces
    ToStringGenerator produceToStringGenerator() {
        return TestArgs.toStringGenerator("slack-config", 7);
    }

    @BeforeEach
    void registerConfig() {
        TestConfigUtil.registerConfig(Map.of());
    }

    private SlackChannelConfig.LocalArguments local(final List<ToolArgs> arguments, final Map<String, String> context) {
        return config.new LocalArguments(arguments, List.of("prompt"), context);
    }

    private SlackChannelConfig.LocalArguments args(final String... nameValues) {
        final List<ToolArgs> arguments = new java.util.ArrayList<>();
        for (int i = 0; i < nameValues.length; i += 2) {
            arguments.add(new ToolArgs(nameValues[i], nameValues[i + 1], true));
        }
        return local(arguments, Map.of());
    }

    private static String startOfDayEpochMinusDays(final int days) {
        return Long.toString(LocalDate.now(ZoneId.systemDefault()).minusDays(days).atStartOfDay(ZoneId.systemDefault()).toEpochSecond());
    }

    @Test
    void channelNamesHaveLeadingHashRemoved() {
        final SlackChannelConfig.LocalArguments local = args(SlackChannel.SLACK_CHANEL_ARG, "#general", SlackChannel.SLACK_CHANEL_2_ARG, "#random#x");

        assertEquals("general", local.getChannel());
        assertEquals("random#x", local.getChannel2());
        assertEquals(List.of("general", "random#x"), local.getChannels());
    }

    @Test
    void configuredChannelTakesPrecedenceOverArgument() {
        TestConfigUtil.registerConfig(Map.of("sb.slack.channel", "#configured"));

        assertEquals("configured", args(SlackChannel.SLACK_CHANEL_ARG, "argument").getChannel());
    }

    @Test
    void channelDetailsSkipBlankSecondChannelAndUseSharedOldestByDefault() {
        final SlackChannelConfig.LocalArguments local = args(SlackChannel.SLACK_CHANEL_ARG, "general", CommonArguments.DAYS_ARG, "7");

        assertEquals(List.of(new ChannelDays("general", startOfDayEpochMinusDays(7))), local.getChannelDetails());
        assertEquals(List.of("general"), local.getChannels());
    }

    @Test
    void secondChannelCanUseItsOwnLookbackWindow() {
        final SlackChannelConfig.LocalArguments local = args(
                SlackChannel.SLACK_CHANEL_ARG, "general",
                SlackChannel.SLACK_CHANEL_2_ARG, "alerts",
                CommonArguments.DAYS_ARG, "7",
                SlackChannel.SLACK_CHANNEL_2_DAYS, "2");

        assertEquals(2, local.getDays2());
        assertEquals(List.of(
                new ChannelDays("general", startOfDayEpochMinusDays(7)),
                new ChannelDays("alerts", startOfDayEpochMinusDays(2))), local.getChannelDetails());
    }

    @Test
    void secondChannelDaysDefaultToPrimaryDays() {
        final SlackChannelConfig.LocalArguments local = args(CommonArguments.DAYS_ARG, "4");

        assertEquals(4, local.getDays2());
        assertEquals(local.getOldest(), local.getOldest2());
    }

    @ParameterizedTest
    @CsvSource({"10,10", "0,0", "-3,0"})
    void daysAreNonNegative(final String value, final int expected) {
        assertEquals(expected, args(CommonArguments.DAYS_ARG, value).getDays());
        assertEquals(expected, args(SlackChannel.SLACK_CHANNEL_2_DAYS, value).getDays2());
    }

    @Test
    void daysDefaultToThirtyAndRejectNonNumericValues() {
        assertEquals(30, args().getDays());
        assertThrows(NumberFormatException.class, () -> args(CommonArguments.DAYS_ARG, "lots").getDays());
    }

    @Test
    void accessTokenIsDecryptedFromContextOrTakenAsPlainText() {
        assertEquals("xoxb-1", local(List.of(), Map.of("slack_access_token", "enc(xoxb-1)")).getSecretAccessToken());
        assertEquals("xoxb-plain", local(List.of(), Map.of("slack_access_token", "xoxb-plain")).getSecretAccessToken());
    }

    @Test
    void accessTokenFailsWhenMissing() {
        assertThrows(InternalFailure.class, () -> args().getSecretAccessToken());
    }

    @Test
    void accessTokenFallsBackToConfig() {
        TestConfigUtil.registerConfig(Map.of("sb.slack.accesstoken", "xoxb-config"));

        assertEquals("xoxb-config", args().getSecretAccessToken());
    }

    @ParameterizedTest
    @CsvSource({"'',120000", "250,250", "-5,0", "slow,120000"})
    void apiDelayDefaultsToTwoMinutesAndIsNonNegative(final String value, final int expected) {
        assertEquals(expected, args(SlackChannel.API_DELAY_ARG, value).getApiDelay());
    }

    @Test
    void ttlSettingsHaveDefaultsAndBounds() {
        assertEquals(1000 * 60 * 60 * 24, args().getSearchTTL());
        assertEquals(0, args(SlackChannel.HISTORY_TTL_ARG, "-1").getSearchTTL());
        assertEquals(60 * 60 * 24, args().getCacheTtl());
        assertEquals(60 * 60 * 24, args(SlackChannel.TTL_SECONDS_ARG, "abc").getCacheTtl());
        assertEquals(0, args(SlackChannel.TTL_SECONDS_ARG, "-9").getCacheTtl());
        assertEquals(120, args(SlackChannel.TTL_SECONDS_ARG, "120").getCacheTtl());
    }

    @Test
    void keywordsComeFromArgumentsAndCanBeAutoGenerated() {
        assertEquals(List.of("alpha", "beta"), args(CommonArguments.KEYWORDS_ARG, " alpha , beta ").getKeywords());
        assertFalse(args().getAutoGenerateKeywords());

        when(keywordsTool.getKeywords(anyMap(), anyList(), anyList())).thenReturn(List.of("delta"));
        assertEquals(List.of("alpha", "delta"),
                args(CommonArguments.KEYWORDS_ARG, "alpha", CommonArguments.AUTO_GENERATE_KEYWORDS_ARG, "true").getKeywords());
    }

    @Test
    void keywordWindowFallsBackToDefault() {
        assertEquals(Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH, args().getKeywordWindow());
        assertEquals(Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH, args(CommonArguments.KEYWORD_WINDOW_ARG, "x").getKeywordWindow());
        assertEquals(300, args(CommonArguments.KEYWORD_WINDOW_ARG, "300").getKeywordWindow());
    }

    @Test
    void ratingSettingsHaveDefaultsAndBounds() {
        assertEquals(10, args().getDefaultRating());
        assertEquals(0, args(CommonArguments.DEFAULT_RATING_ARG, "-3").getDefaultRating());
        assertEquals(10, args(CommonArguments.DEFAULT_RATING_ARG, "abc").getDefaultRating());
        assertEquals(0, args().getContextFilterMinimumRating());
        assertEquals(6, args(CommonArguments.CONTEXT_FILTER_MINIMUM_RATING_ARG, "6").getContextFilterMinimumRating());
        assertEquals("", args().getContextFilterQuestion());
        assertEquals("relevant?", args(CommonArguments.CONTENT_RATING_QUESTION_ARG, "relevant?").getContextFilterQuestion());
        assertFalse(args().isContextFilterUpperLimit());
        assertTrue(args(CommonArguments.FILTER_GREATER_THAN_ARG, "true").isContextFilterUpperLimit());
    }

    @Test
    void summarySettingsHaveDefaults() {
        // sb.slack.summarizedocument defaults to "false", and config takes precedence over arguments
        assertFalse(args().getSummarizeDocument());
        assertFalse(args(CommonArguments.SUMMARIZE_DOCUMENT_ARG, "true").getSummarizeDocument());
        assertEquals("Summarise the document in three paragraphs", args().getDocumentSummaryPrompt());
        assertEquals("one line", args(CommonArguments.SUMMARIZE_DOCUMENT_PROMPT_ARG, "one line").getDocumentSummaryPrompt());
    }

    @Test
    void summarizeDocumentCanBeEnabledInConfig() {
        TestConfigUtil.registerConfig(Map.of("sb.slack.summarizedocument", "true"));

        assertTrue(args().getSummarizeDocument());
    }

    @Test
    void ignoredUsersAreSplitAndBlankEntriesDropped() {
        assertEquals(List.of("bot1", "bot2"), args(SlackChannel.IGNORED_USERS_ARG, "bot1, ,bot2").getIgnoredUsers());
        assertEquals(List.of(), args().getIgnoredUsers());
    }

    @Test
    void flagsHooksAndEntityAreReadFromArgumentsAndContext() {
        assertTrue(args(CommonArguments.SKIP_EMPTY_IN_LAST_DURATION, "true").isSkipEmptyInLastDuration());
        assertFalse(args().isSkipEmptyInLastDuration());
        assertEquals("pre", args(CommonArguments.PREPROCESSOR_HOOKS_ARG, "pre").getPreprocessingHooks());
        assertEquals("init", args(CommonArguments.PREINITIALIZATION_HOOKS_ARG, "init").getPreinitializationHooks());
        assertEquals("post", args(CommonArguments.POSTINFERENCE_HOOKS_ARG, "post").getPostInferenceHooks());
        assertEquals("Acme", local(List.of(), Map.of(CommonArguments.ENTITY_NAME_CONTEXT_ARG, "Acme")).getEntity());
    }

    @Test
    void toStringAndHashCodeDelegateToTheGenerator() {
        assertEquals("slack-config", args().toString());
        assertEquals(7, args().hashCode());
    }
}
