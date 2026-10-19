package secondbrain.domain.tools.planhat;

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
import secondbrain.domain.date.DateParser;
import secondbrain.domain.objects.ToStringGenerator;
import secondbrain.domain.test.TestArgs;
import secondbrain.domain.test.TestConfigUtil;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tools.CommonArguments;
import secondbrain.domain.validate.ValidateStringBlank;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME;
import static org.junit.jupiter.api.Assertions.*;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(PlanhatConversationStatsConfig.class)
@AddBeanClasses(ArgsAccessorSimple.class)
@AddBeanClasses(ValidateStringBlank.class)
class PlanhatConversationStatsConfigTest {
    private static final int DEFAULT_TTL = 60 * 60 * 24 * 7;

    private final Map<String, String> settings = new HashMap<>();

    @Inject
    private PlanhatConversationStatsConfig config;

    @Produces
    @Identifier("everything")
    DateParser produceDateParser() {
        return ZonedDateTime::parse;
    }

    @Produces
    ToStringGenerator produceToStringGenerator() {
        return TestArgs.toStringGenerator("planhat-conversation-stats-config", 31);
    }

    @BeforeEach
    void registerConfig() {
        TestConfigUtil.registerConfig(settings);
    }

    /**
     * Registers a configuration value. Must be called before the config bean is first used, as CDI creates it lazily.
     */
    private void configure(final String key, final String value) {
        settings.put(key, value);
        TestConfigUtil.registerConfig(settings);
    }

    private PlanhatConversationStatsConfig.LocalArguments local(final String prompt, final Map<String, String> context, final boolean trusted, final String... nameValues) {
        final List<ToolArgs> arguments = new ArrayList<>();
        for (int i = 0; i < nameValues.length; i += 2) {
            arguments.add(new ToolArgs(nameValues[i], nameValues[i + 1], trusted));
        }
        return config.new LocalArguments(arguments, List.of(prompt), context);
    }

    private PlanhatConversationStatsConfig.LocalArguments args(final String... nameValues) {
        return local("prompt", Map.of(), true, nameValues);
    }

    private PlanhatConversationStatsConfig.LocalArguments context(final Map<String, String> context) {
        return local("prompt", context, true);
    }

    private static <T> void assertTime(final Supplier<T> expected, final Supplier<T> actual) {
        final T before = expected.get();
        final T value = actual.get();
        final T after = expected.get();
        assertTrue(value.equals(before) || value.equals(after), () -> "expected " + before + " but was " + value);
    }

    @Test
    void companyIdPrecedenceIsConfigThenContextThenArgument() {
        assertEquals("", args().getCompanyId());
        assertEquals("arg-co", args(PlanhatConversationStats.COMPANY_ID_ARG, "arg-co").getCompanyId());
        assertEquals("context-co", local("prompt", Map.of(PlanhatConversationStats.COMPANY_ID_ARG, "context-co"), true,
                PlanhatConversationStats.COMPANY_ID_ARG, "arg-co").getCompanyId());
    }

    @Test
    void configuredCompanyIdWins() {
        configure("sb.planhat.company", "config-co");

        assertEquals("config-co", local("prompt", Map.of(PlanhatConversationStats.COMPANY_ID_ARG, "context-co"), true,
                PlanhatConversationStats.COMPANY_ID_ARG, "arg-co").getCompanyId());
    }

    @Test
    void urlDefaultsToTheUsApiEvenWhenTheContextHasAUrl() {
        // sb.planhat.url has a default value, so the planhat_url context value is never used
        assertEquals("https://api-us4.planhat.com", context(Map.of("planhat_url", "https://context.planhat.test")).getUrl());
    }

    @Test
    void configuredUrlIsUsed() {
        configure("sb.planhat.url", "https://config.planhat.test");

        assertEquals("https://config.planhat.test", context(Map.of("planhat_url", "https://context.planhat.test")).getUrl());
    }

    @Test
    void tokenComesFromConfigThenContext() {
        assertEquals("", context(Map.of()).getSecretToken());
        assertEquals("", context(Map.of("planhat_token", " ")).getSecretToken());
        assertEquals("context-token", context(Map.of("planhat_token", "context-token")).getSecretToken());
    }

    @Test
    void configuredTokenWins() {
        configure("sb.planhat.accesstoken", "config-token");

        assertEquals("config-token", context(Map.of("planhat_token", "context-token")).getSecretToken());
    }

    @ParameterizedTest
    @CsvSource({"600,600", "0,0", "-1,0", "week," + DEFAULT_TTL})
    void searchTtlIsNonNegativeAndDefaultsWhenInvalid(final String value, final int expected) {
        assertEquals(expected, args(PlanhatConversationStats.SEARCH_TTL_ARG, value).getSearchTTL());
    }

    @Test
    void searchTtlDefaultsToOneWeek() {
        assertEquals(DEFAULT_TTL, args().getSearchTTL());
    }

    @Test
    void configuredSearchTtlWins() {
        configure("sb.planhat.searchttl", "30");
        assertEquals(30, args(PlanhatConversationStats.SEARCH_TTL_ARG, "60").getSearchTTL());
    }

    @ParameterizedTest
    @CsvSource({"4,4", "0,0", "-2,0", "lots,0"})
    void rawHoursAndDaysAreNonNegativeIntegers(final String value, final int expected) {
        assertEquals(expected, args(CommonArguments.HOURS_ARG, value).getRawHours());
        assertEquals(expected, args(CommonArguments.DAYS_ARG, value).getRawDays());
    }

    @Test
    void configuredHoursAndDaysWin() {
        configure("sb.planhat.hours", "3");
        configure("sb.planhat.days", "11");

        final PlanhatConversationStatsConfig.LocalArguments local = args(CommonArguments.HOURS_ARG, "1", CommonArguments.DAYS_ARG, "2");

        assertEquals(3, local.getHours());
        assertEquals(11, local.getDays());
    }

    @Test
    void trustedHoursAndDaysAreUsedAsIs() {
        final PlanhatConversationStatsConfig.LocalArguments local = local("conversations", Map.of(), true,
                CommonArguments.HOURS_ARG, "5", CommonArguments.DAYS_ARG, "2");

        assertEquals(5, local.getHours());
        assertEquals(2, local.getDays());
    }

    @ParameterizedTest(name = "{index}: {0}")
    @CsvSource({
            "'conversations in the last 3 days', 6, 3, 0, 3",
            "'conversations in the last 5 hours', 0, 5, 5, 0",
            "'conversations in the last 2 days and 4 hours', 4, 2, 4, 2",
            "'conversations in the last 4 hours', 4, 9, 4, 0",
            "'all conversations', 4, 9, 0, 0"
    })
    void untrustedHoursAndDaysAreCorrectedFromThePrompt(final String prompt, final String hours, final String days,
                                                        final int expectedHours, final int expectedDays) {
        final PlanhatConversationStatsConfig.LocalArguments local = local(prompt, Map.of(), false,
                CommonArguments.HOURS_ARG, hours, CommonArguments.DAYS_ARG, days);

        assertEquals(expectedHours, local.getHours());
        assertEquals(expectedDays, local.getDays());
    }

    @Test
    void periodsAreParsedFormattedAndBlankByDefault() {
        final PlanhatConversationStatsConfig.LocalArguments local = args(
                CommonArguments.START_DATE, "2024-05-01T08:00:00-04:00",
                CommonArguments.END_DATE, "2024-05-02T00:00:00Z");

        assertEquals("2024-05-01T08:00:00-04:00", local.getStartPeriod());
        assertEquals("2024-05-02T00:00:00Z", local.getEndPeriod());
        assertEquals("", args().getStartPeriod());
        assertEquals("", args().getEndPeriod());
    }

    @Test
    void configuredPeriodsWin() {
        configure("sb.planhat.startperiod", "2023-01-01T00:00:00Z");
        configure("sb.planhat.endperiod", "2023-01-31T00:00:00Z");

        final PlanhatConversationStatsConfig.LocalArguments local = args(
                CommonArguments.START_DATE, "2024-05-01T00:00:00Z",
                CommonArguments.END_DATE, "2024-05-02T00:00:00Z");

        assertEquals("2023-01-01T00:00:00Z", local.getStartPeriod());
        assertEquals("2023-01-31T00:00:00Z", local.getEndPeriod());
    }

    @Test
    void startDateDefaultsToTheStartOfYesterday() {
        assertTime(() -> OffsetDateTime.now(ZoneId.systemDefault()).truncatedTo(ChronoUnit.DAYS).minusDays(1).format(ISO_OFFSET_DATE_TIME),
                () -> args().getStartDate());
    }

    @Test
    void startDateUsesDaysAndHours() {
        assertTime(() -> OffsetDateTime.now(ZoneId.systemDefault()).truncatedTo(ChronoUnit.DAYS).minusDays(4).format(ISO_OFFSET_DATE_TIME),
                () -> args(CommonArguments.DAYS_ARG, "4").getStartDate());
        assertTime(() -> OffsetDateTime.now(ZoneId.systemDefault()).truncatedTo(ChronoUnit.HOURS).minusHours(6).format(ISO_OFFSET_DATE_TIME),
                () -> args(CommonArguments.HOURS_ARG, "6").getStartDate());
        assertTime(() -> OffsetDateTime.now(ZoneId.systemDefault()).truncatedTo(ChronoUnit.HOURS).minusDays(1).minusHours(2).format(ISO_OFFSET_DATE_TIME),
                () -> args(CommonArguments.DAYS_ARG, "1", CommonArguments.HOURS_ARG, "2").getStartDate());
    }

    @Test
    void endDateIsTheStartOfTomorrow() {
        assertTime(() -> OffsetDateTime.now(ZoneId.systemDefault()).truncatedTo(ChronoUnit.DAYS).plusDays(1).format(ISO_OFFSET_DATE_TIME),
                () -> args().getEndDate());
    }

    @Test
    void zonedDatesPreferExplicitPeriods() {
        final PlanhatConversationStatsConfig.LocalArguments local = args(
                CommonArguments.START_DATE, "2024-05-01T08:00:00-04:00",
                CommonArguments.END_DATE, "2024-05-02T00:00:00Z",
                CommonArguments.DAYS_ARG, "30");

        assertEquals(ZonedDateTime.parse("2024-05-01T08:00:00-04:00"), local.getStartZonedDateTime());
        assertEquals(ZonedDateTime.parse("2024-05-02T00:00:00Z"), local.getEndZonedDateTime());
    }

    @Test
    void zonedDatesFallBackToTheCalculatedRange() {
        final PlanhatConversationStatsConfig.LocalArguments local = args(CommonArguments.DAYS_ARG, "2");

        assertTime(() -> ZonedDateTime.parse(OffsetDateTime.now(ZoneId.systemDefault()).truncatedTo(ChronoUnit.DAYS).minusDays(2).format(ISO_OFFSET_DATE_TIME)),
                local::getStartZonedDateTime);
        assertTime(() -> ZonedDateTime.parse(OffsetDateTime.now(ZoneId.systemDefault()).truncatedTo(ChronoUnit.DAYS).plusDays(1).format(ISO_OFFSET_DATE_TIME)),
                local::getEndZonedDateTime);
    }

    @Test
    void toStringAndHashCodeDelegateToTheGenerator() {
        assertEquals("planhat-conversation-stats-config", args().toString());
        assertEquals(31, args().hashCode());
    }
}
