package secondbrain.domain.tools.slack;

import io.smallrye.common.annotation.Identifier;
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
import secondbrain.domain.args.ArgsAccessorSimple;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.date.DateParser;
import secondbrain.domain.encryption.Encryptor;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.keyword.KeywordExtractor;
import secondbrain.domain.objects.ToStringGenerator;
import secondbrain.domain.test.TestArgs;
import secondbrain.domain.test.TestConfigUtil;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tools.CommonArguments;
import secondbrain.domain.validate.ValidateStringBlank;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(SlackSearchConfig.class)
@AddBeanClasses(ArgsAccessorSimple.class)
@AddBeanClasses(ValidateStringBlank.class)
class SlackSearchConfigTest {
    private final List<String> extractedText = new CopyOnWriteArrayList<>();

    @Inject
    private SlackSearchConfig config;

    @Produces
    KeywordExtractor produceKeywordExtractor() {
        return text -> {
            extractedText.add(text);
            return List.of("beta", "gamma");
        };
    }

    @Produces
    @Identifier("everything")
    DateParser produceDateParser() {
        return ZonedDateTime::parse;
    }

    @Produces
    @Identifier("AES")
    Encryptor produceEncryptor() {
        return TestArgs.encryptor();
    }

    @Produces
    ToStringGenerator produceToStringGenerator() {
        return TestArgs.toStringGenerator("slack-search-config", 13);
    }

    @BeforeEach
    void registerConfig() {
        TestConfigUtil.registerConfig(Map.of());
    }

    private SlackSearchConfig.LocalArguments local(final List<ToolArgs> arguments, final Map<String, String> context) {
        return config.new LocalArguments(arguments, List.of("first prompt", "second prompt"), context);
    }

    private SlackSearchConfig.LocalArguments args(final String... nameValues) {
        final List<ToolArgs> arguments = new ArrayList<>();
        for (int i = 0; i < nameValues.length; i += 2) {
            arguments.add(new ToolArgs(nameValues[i], nameValues[i + 1], true));
        }
        return local(arguments, Map.of());
    }

    @Test
    void searchKeywordsAreSplitTrimmedAndDeduplicated() {
        assertEquals(Set.of("alpha", "beta"), args(SlackSearch.SLACK_SEARCH_KEYWORDS_ARG, " alpha, beta ,alpha,").getSearchKeywords());
        assertEquals(Set.of(), args().getSearchKeywords());
        assertTrue(extractedText.isEmpty());
    }

    @Test
    void searchKeywordsIncludeGeneratedKeywordsFromAllPrompts() {
        final Set<String> keywords = args(SlackSearch.SLACK_SEARCH_KEYWORDS_ARG, "alpha,beta", SlackSearch.SLACK_GENERATE_KEYWORDS_ARG, "true").getSearchKeywords();

        assertEquals(Set.of("alpha", "beta", "gamma"), keywords);
        assertEquals(List.of("first prompt\nsecond prompt"), extractedText);
    }

    @Test
    void searchKeywordsFromConfigTakePrecedenceOverArguments() {
        TestConfigUtil.registerConfig(Map.of("sb.slack.searchkeywords", "configured"));

        assertEquals(Set.of("configured"), args(SlackSearch.SLACK_SEARCH_KEYWORDS_ARG, "argument").getSearchKeywords());
    }

    @Test
    void filterKeywordsAreFollowedByGeneratedKeywordsWithoutDuplicates() {
        assertEquals(List.of("alpha", "zeta"), args(CommonArguments.KEYWORDS_ARG, "alpha, zeta").getKeywords());
        // Supplied keywords keep their order, followed by the generated keywords
        assertEquals(List.of("alpha", "zeta", "beta", "gamma"),
                args(CommonArguments.KEYWORDS_ARG, "alpha,zeta", SlackSearch.SLACK_GENERATE_KEYWORDS_ARG, "true").getKeywords());
        // A generated keyword that was also supplied is only included once
        assertEquals(List.of("gamma", "alpha", "beta"),
                args(CommonArguments.KEYWORDS_ARG, "gamma,alpha", SlackSearch.SLACK_GENERATE_KEYWORDS_ARG, "true").getKeywords());
    }

    @ParameterizedTest
    @CsvSource({"true,true", "yes,true", "false,false", "nonsense,false"})
    void generateKeywordsParsesBooleanText(final String value, final boolean expected) {
        assertEquals(expected, args(SlackSearch.SLACK_GENERATE_KEYWORDS_ARG, value).getGenerateKeywords());
    }

    @Test
    void generateKeywordsDefaultsToFalse() {
        assertFalse(args().getGenerateKeywords());
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
        // Config must be registered before the config bean is first used, as CDI creates it lazily
        TestConfigUtil.registerConfig(Map.of("sb.slack.accesstoken", "xoxb-config"));

        assertEquals("xoxb-config", args().getSecretAccessToken());
    }

    @ParameterizedTest
    @CsvSource({"7,7", "0,0", "-5,0"})
    void daysAreNonNegative(final String value, final int expected) {
        assertEquals(expected, args(CommonArguments.DAYS_ARG, value).getDays());
    }

    @Test
    void daysDefaultToThirty() {
        assertEquals(30, args().getDays());
    }

    @ParameterizedTest
    @CsvSource({"'',30", "week,30", "7,7", "0,0", "-3,0"})
    void daysFallBackToThirtyForMissingOrInvalidValuesAndAreNonNegative(final String value, final int expected) {
        assertEquals(expected, args(CommonArguments.DAYS_ARG, value).getDays());
    }

    @Test
    void daysFromConfigTakePrecedence() {
        TestConfigUtil.registerConfig(Map.of("sb.slack.days", "14"));
        assertEquals(14, args(CommonArguments.DAYS_ARG, "7").getDays());
    }

    @Test
    void startDateDefaultsToThirtyDaysBeforeNow() {
        final ZonedDateTime expected = ZonedDateTime.now(ZoneOffset.UTC).minusDays(30);

        final ZonedDateTime actual = args().getStartDate();

        assertTrue(Duration.between(expected, actual).abs().toSeconds() < 60, () -> expected + " vs " + actual);
    }

    @Test
    void startDateIsParsedWhenSupplied() {
        assertEquals(ZonedDateTime.of(2024, 3, 1, 0, 0, 0, 0, ZoneOffset.UTC),
                args(CommonArguments.START_DATE, "2024-03-01T00:00:00Z", CommonArguments.DAYS_ARG, "2").getStartDate());
    }

    @Test
    void startDateDefaultsToDaysBeforeNow() {
        final ZonedDateTime expected = ZonedDateTime.now(ZoneOffset.UTC).minusDays(3);

        final ZonedDateTime actual = args(CommonArguments.DAYS_ARG, "3").getStartDate();

        assertTrue(Duration.between(expected, actual).abs().toSeconds() < 60, () -> expected + " vs " + actual);
    }

    @Test
    void searchTtlDefaultsToSixDaysInMillisecondsAndIsNonNegative() {
        assertEquals(1000 * 60 * 60 * 24 * 6, args().getSearchTTL());
        assertEquals(0, args(SlackSearch.SEARCH_TTL_ARG, "-1").getSearchTTL());
        assertEquals(500, args(SlackSearch.SEARCH_TTL_ARG, "500").getSearchTTL());
        assertThrows(NumberFormatException.class, () -> args(SlackSearch.SEARCH_TTL_ARG, "forever").getSearchTTL());
    }

    @ParameterizedTest
    @CsvSource({"'',120000", "250,250", "-5,0", "slow,120000"})
    void apiDelayDefaultsToTwoMinutesAndIsNonNegative(final String value, final int expected) {
        assertEquals(expected, args(SlackSearch.API_DELAY_ARG, value).getApiDelay());
    }

    @Test
    void ignoredChannelsAreTrimmedAndLoseOnlyTheLeadingHash() {
        assertEquals(List.of("general", "random", "a#b"), args(SlackSearch.SLACK_IGNORE_CHANNELS_ARG, "#general, random ,, #a#b").getIgnoreChannels());
        assertEquals(List.of(), args().getIgnoreChannels());
    }

    @Test
    void keywordWindowFallsBackToDefault() {
        assertEquals(Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH, args().getKeywordWindow());
        assertEquals(Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH, args(CommonArguments.KEYWORD_WINDOW_ARG, "wide").getKeywordWindow());
        assertEquals(300, args(CommonArguments.KEYWORD_WINDOW_ARG, "300").getKeywordWindow());
    }

    @Test
    void entityIsReadFromContextOrArguments() {
        assertEquals("Acme", local(List.of(), Map.of(CommonArguments.ENTITY_NAME_CONTEXT_ARG, "Acme")).getEntity());
        assertEquals("Globex", args(CommonArguments.ENTITY_NAME_CONTEXT_ARG, "Globex").getEntity());
        assertEquals("", args().getEntity());
    }

    @Test
    void ratingSettingsHaveDefaultsAndBounds() {
        assertEquals("", args().getContextFilterQuestion());
        assertEquals("relevant?", args(CommonArguments.CONTENT_RATING_QUESTION_ARG, "relevant?").getContextFilterQuestion());
        assertEquals(0, args().getContextFilterMinimumRating());
        assertEquals(6, args(CommonArguments.CONTEXT_FILTER_MINIMUM_RATING_ARG, "6").getContextFilterMinimumRating());
        assertEquals(0, args(CommonArguments.CONTEXT_FILTER_MINIMUM_RATING_ARG, "high").getContextFilterMinimumRating());
        assertEquals(10, args().getDefaultRating());
        assertEquals(4, args(CommonArguments.DEFAULT_RATING_ARG, "4").getDefaultRating());
        assertEquals(0, args(CommonArguments.DEFAULT_RATING_ARG, "-3").getDefaultRating());
        assertEquals(10, args(CommonArguments.DEFAULT_RATING_ARG, "abc").getDefaultRating());
        assertFalse(args().isContextFilterUpperLimit());
        assertTrue(args(CommonArguments.FILTER_GREATER_THAN_ARG, "true").isContextFilterUpperLimit());
    }

    @Test
    void ratingSettingsFromConfigTakePrecedence() {
        TestConfigUtil.registerConfig(Map.of(
                "sb.slack.contextFilterQuestion", "configured?",
                "sb.slack.contextFilterMinimumRating", "8",
                "sb.slack.contextFilterDefaultRating", "2",
                "sb.slack.contextFilterGreaterThan", "true"));

        final SlackSearchConfig.LocalArguments local = args(
                CommonArguments.CONTENT_RATING_QUESTION_ARG, "argument?",
                CommonArguments.CONTEXT_FILTER_MINIMUM_RATING_ARG, "1",
                CommonArguments.DEFAULT_RATING_ARG, "9",
                CommonArguments.FILTER_GREATER_THAN_ARG, "false");

        assertEquals("configured?", local.getContextFilterQuestion());
        assertEquals(8, local.getContextFilterMinimumRating());
        assertEquals(2, local.getDefaultRating());
        assertTrue(local.isContextFilterUpperLimit());
    }

    @Test
    void hooksAreReadFromArguments() {
        assertEquals("pre", args(CommonArguments.PREPROCESSOR_HOOKS_ARG, "pre").getPreprocessingHooks());
        assertEquals("init", args(CommonArguments.PREINITIALIZATION_HOOKS_ARG, "init").getPreinitializationHooks());
        assertEquals("post", args(CommonArguments.POSTINFERENCE_HOOKS_ARG, "post").getPostInferenceHooks());
        assertEquals("", args().getPreprocessingHooks());
        assertEquals("", args().getPreinitializationHooks());
        assertEquals("", args().getPostInferenceHooks());
    }

    @Test
    void cacheTtlDefaultsToOneDayAndIsNonNegative() {
        assertEquals(60 * 60 * 24, args().getCacheTtl());
        assertEquals(60 * 60 * 24, args(SlackSearch.TTL_SECONDS_ARG, "abc").getCacheTtl());
        assertEquals(0, args(SlackSearch.TTL_SECONDS_ARG, "-9").getCacheTtl());
        assertEquals(120, args(SlackSearch.TTL_SECONDS_ARG, "120").getCacheTtl());
    }

    @Test
    void skipEmptyInLastDurationDefaultsToFalse() {
        assertFalse(args().isSkipEmptyInLastDuration());
        assertTrue(args(CommonArguments.SKIP_EMPTY_IN_LAST_DURATION, "true").isSkipEmptyInLastDuration());
        assertFalse(args(CommonArguments.SKIP_EMPTY_IN_LAST_DURATION, "yes").isSkipEmptyInLastDuration());
    }

    @Test
    void toStringAndHashCodeDelegateToTheGenerator() {
        assertEquals("slack-search-config", args().toString());
        assertEquals(13, args().hashCode());
    }
}
