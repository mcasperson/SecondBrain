package secondbrain.domain.tools.zendesk;

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
import secondbrain.domain.encryption.Encryptor;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.objects.ToStringGenerator;
import secondbrain.domain.test.TestArgs;
import secondbrain.domain.test.TestConfigUtil;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tools.CommonArguments;
import secondbrain.domain.validate.ValidateStringBlank;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import static java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME;
import static org.junit.jupiter.api.Assertions.*;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(ZenDeskStatsConfig.class)
@AddBeanClasses(ArgsAccessorSimple.class)
@AddBeanClasses(ValidateStringBlank.class)
class ZenDeskStatsConfigTest {
    private static final int DEFAULT_TTL = 60 * 60 * 24 * 90;

    private final Map<String, String> settings = new HashMap<>();

    @Inject
    private ZenDeskStatsConfig config;

    @Produces
    @Identifier("AES")
    Encryptor produceEncryptor() {
        return TestArgs.encryptor();
    }

    @Produces
    @Identifier("everything")
    DateParser produceDateParser() {
        return ZonedDateTime::parse;
    }

    @Produces
    ToStringGenerator produceToStringGenerator() {
        return TestArgs.toStringGenerator("zendesk-stats-config", 17);
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

    private ZenDeskStatsConfig.LocalArguments local(final String prompt, final Map<String, String> context, final List<ToolArgs> arguments) {
        return config.new LocalArguments(arguments, List.of(prompt), context);
    }

    private ZenDeskStatsConfig.LocalArguments context(final Map<String, String> context) {
        return local("prompt", context, List.of());
    }

    private ZenDeskStatsConfig.LocalArguments args(final String... nameValues) {
        return withArgs("prompt", true, nameValues);
    }

    /**
     * Arguments from an LLM are untrusted, which enables the hours/days keyword correction.
     */
    private ZenDeskStatsConfig.LocalArguments withArgs(final String prompt, final boolean trusted, final String... nameValues) {
        final List<ToolArgs> arguments = new ArrayList<>();
        for (int i = 0; i < nameValues.length; i += 2) {
            arguments.add(new ToolArgs(nameValues[i], nameValues[i + 1], trusted));
        }
        return local(prompt, Map.of(), arguments);
    }

    private static Function<ZenDeskStatsConfig.LocalArguments, String> secret(final String name) {
        return switch (name) {
            case "token" -> ZenDeskStatsConfig.LocalArguments::getSecretToken;
            case "url" -> ZenDeskStatsConfig.LocalArguments::getUrl;
            case "user" -> ZenDeskStatsConfig.LocalArguments::getUser;
            default -> throw new IllegalArgumentException(name);
        };
    }

    /**
     * Asserts the value matches the expected value calculated either before or after the call, so the test does not
     * fail if the clock crosses an hour or day boundary.
     */
    private static void assertDate(final Supplier<String> expected, final Supplier<String> actual) {
        final String before = expected.get();
        final String value = actual.get();
        final String after = expected.get();
        assertTrue(value.equals(before) || value.equals(after), () -> "expected " + before + " but was " + value);
    }

    @Test
    void organizationPrecedenceIsConfigThenContextThenArgument() {
        assertEquals("", args().getOrganization());
        assertEquals("org-arg", args(ZenDeskStats.ZENDESK_ORGANIZATION_ARG, "org-arg").getOrganization());
        assertEquals("org-context", local("prompt", Map.of(ZenDeskStats.ZENDESK_ORGANIZATION_ARG, "org-context"),
                List.of(new ToolArgs(ZenDeskStats.ZENDESK_ORGANIZATION_ARG, "org-arg", true))).getOrganization());
    }

    @Test
    void configuredOrganizationWins() {
        configure("sb.zendesk.organization", "org-config");

        assertEquals("org-config", local("prompt", Map.of(ZenDeskStats.ZENDESK_ORGANIZATION_ARG, "org-context"),
                List.of(new ToolArgs(ZenDeskStats.ZENDESK_ORGANIZATION_ARG, "org-arg", true))).getOrganization());
    }

    @ParameterizedTest
    @CsvSource({"token,zendesk_access_token", "url,zendesk_url", "user,zendesk_user"})
    void secretsAreDecryptedFromTheContextOrTakenAsPlainText(final String name, final String contextKey) {
        assertEquals("decrypted", secret(name).apply(context(Map.of(contextKey, "enc(decrypted)"))));
        assertEquals("plain", secret(name).apply(context(Map.of(contextKey, "plain"))));
    }

    @ParameterizedTest
    @CsvSource({"token,zendesk_access_token,sb.zendesk.accesstoken", "url,zendesk_url,sb.zendesk.url", "user,zendesk_user,sb.zendesk.user"})
    void secretsFallBackToConfigWhenTheContextIsBlankOrMissing(final String name, final String contextKey, final String configKey) {
        configure(configKey, "configured");

        assertEquals("configured", secret(name).apply(context(Map.of(contextKey, " "))));
        assertEquals("configured", secret(name).apply(context(Map.of())));
        // The context takes precedence over config
        assertEquals("context", secret(name).apply(context(Map.of(contextKey, "context"))));
    }

    @ParameterizedTest
    @CsvSource({"token,Failed to get Zendesk access token", "url,Failed to get Zendesk URL", "user,Failed to get Zendesk User"})
    void missingSecretsThrow(final String name, final String message) {
        final InternalFailure ex = assertThrows(InternalFailure.class, () -> secret(name).apply(context(Map.of())));

        assertEquals(message, ex.getMessage());
    }

    @Test
    void authHeaderCombinesUserAndToken() {
        final String header = context(Map.of("zendesk_user", "agent@example.com", "zendesk_access_token", "enc(secret)")).getSecretAuthHeader();

        assertEquals("Basic " + Base64.getEncoder().encodeToString("agent@example.com/token:secret".getBytes(StandardCharsets.UTF_8)), header);
    }

    @Test
    void authHeaderRequiresAToken() {
        assertThrows(InternalFailure.class, () -> context(Map.of("zendesk_user", "agent")).getSecretAuthHeader());
    }

    @ParameterizedTest
    @CsvSource({"3600,3600", "0,0", "-5,0", "soon," + DEFAULT_TTL})
    void searchTtlIsNonNegativeAndDefaultsWhenInvalid(final String value, final int expected) {
        assertEquals(expected, args(ZenDeskStats.ZENDESK_HISTORY_TTL_ARG, value).getSearchTTL());
    }

    @Test
    void searchTtlDefaultsToNinetyDays() {
        assertEquals(DEFAULT_TTL, args().getSearchTTL());
    }

    @Test
    void configuredSearchTtlWins() {
        configure("sb.zendesk.historyttl", "60");
        assertEquals(60, args(ZenDeskStats.ZENDESK_HISTORY_TTL_ARG, "120").getSearchTTL());
    }

    @ParameterizedTest
    @CsvSource({"4,4", "0,0", "-2,0", "many,0"})
    void rawHoursAndDaysAreNonNegativeIntegers(final String value, final int expected) {
        assertEquals(expected, args(CommonArguments.HOURS_ARG, value).getRawHours());
        assertEquals(expected, args(CommonArguments.DAYS_ARG, value).getRawDays());
    }

    @Test
    void rawHoursAndDaysDefaultToZero() {
        assertEquals(0, args().getRawHours());
        assertEquals(0, args().getRawDays());
    }

    @Test
    void configuredHoursAndDaysWin() {
        configure("sb.zendesk.hours", "6");
        configure("sb.zendesk.days", "8");
        final ZenDeskStatsConfig.LocalArguments local = args(CommonArguments.HOURS_ARG, "1", CommonArguments.DAYS_ARG, "2");
        assertEquals(6, local.getRawHours());
        assertEquals(8, local.getRawDays());
    }

    @Test
    void trustedHoursAndDaysAreUsedAsIs() {
        // The prompt mentions neither unit, but trusted values are not corrected
        final ZenDeskStatsConfig.LocalArguments local = withArgs("show tickets", true, CommonArguments.HOURS_ARG, "5", CommonArguments.DAYS_ARG, "2");

        assertEquals(5, local.getHours());
        assertEquals(2, local.getDays());
    }

    @ParameterizedTest(name = "{index}: {0}")
    @CsvSource({
            // Units not mentioned in the prompt are zeroed
            "'tickets from the last 3 days', 0, 3, 0, 3",
            "'tickets from the last 3 days', 7, 3, 0, 3",
            // An LLM that put the hours in the days argument is corrected
            "'tickets from the last 5 hours', 0, 5, 5, 0",
            // When both units are mentioned the values are kept
            "'tickets from the last 2 days and 4 hours', 4, 2, 4, 2",
            // When both units are mentioned a zero value is not swapped
            "'tickets from the last 2 days and some hours', 0, 2, 0, 2",
            "'tickets from the last 6 HOURS', 6, 0, 6, 0",
            // The days keyword matches inside other words such as today
            "'tickets from today', 0, 4, 0, 4"
    })
    void untrustedHoursAndDaysAreCorrectedFromThePrompt(final String prompt, final String hours, final String days,
                                                        final int expectedHours, final int expectedDays) {
        final ZenDeskStatsConfig.LocalArguments local = withArgs(prompt, false, CommonArguments.HOURS_ARG, hours, CommonArguments.DAYS_ARG, days);

        assertEquals(expectedHours, local.getHours());
        assertEquals(expectedDays, local.getDays());
    }

    @Test
    void onlyOneUntrustedArgumentEnablesTheCorrection() {
        // Hours is the trusted default of 0 while days is untrusted
        final ZenDeskStatsConfig.LocalArguments local = withArgs("tickets from the last 5 hours", false, CommonArguments.DAYS_ARG, "5");

        assertEquals(5, local.getHours());
        assertEquals(0, local.getDays());
    }

    @Test
    void periodsAreParsedAndFormatted() {
        final ZenDeskStatsConfig.LocalArguments local = args(
                CommonArguments.START_DATE, "2024-03-01T10:15:30+02:00",
                CommonArguments.END_DATE, "2024-03-08T00:00:00Z");

        assertEquals("2024-03-01T10:15:30+02:00", local.getStartPeriod());
        assertEquals("2024-03-08T00:00:00Z", local.getEndPeriod());
    }

    @Test
    void periodsAreBlankWhenNotSupplied() {
        assertEquals("", args().getStartPeriod());
        assertEquals("", args().getEndPeriod());
    }

    @Test
    void configuredPeriodsWin() {
        configure("sb.zendesk.startperiod", "2023-01-01T00:00:00Z");
        configure("sb.zendesk.endperiod", "2023-02-01T00:00:00Z");

        final ZenDeskStatsConfig.LocalArguments local = args(
                CommonArguments.START_DATE, "2024-03-01T00:00:00Z",
                CommonArguments.END_DATE, "2024-03-08T00:00:00Z");

        assertEquals("2023-01-01T00:00:00Z", local.getStartPeriod());
        assertEquals("2023-02-01T00:00:00Z", local.getEndPeriod());
    }

    @Test
    void invalidPeriodsThrow() {
        assertThrows(RuntimeException.class, () -> args(CommonArguments.START_DATE, "yesterday").getStartPeriod());
    }

    @Test
    void startDateDefaultsToTheStartOfYesterday() {
        assertDate(() -> OffsetDateTime.now(ZoneId.systemDefault()).truncatedTo(ChronoUnit.DAYS).minusDays(1).format(ISO_OFFSET_DATE_TIME),
                () -> args().getStartDate());
    }

    @Test
    void startDateIsDaysBeforeTheStartOfToday() {
        assertDate(() -> OffsetDateTime.now(ZoneId.systemDefault()).truncatedTo(ChronoUnit.DAYS).minusDays(3).format(ISO_OFFSET_DATE_TIME),
                () -> args(CommonArguments.DAYS_ARG, "3").getStartDate());
    }

    @Test
    void startDateIsHoursBeforeTheStartOfTheCurrentHour() {
        assertDate(() -> OffsetDateTime.now(ZoneId.systemDefault()).truncatedTo(ChronoUnit.HOURS).minusHours(5).format(ISO_OFFSET_DATE_TIME),
                () -> args(CommonArguments.HOURS_ARG, "5").getStartDate());
    }

    @Test
    void startDateCombinesDaysAndHours() {
        assertDate(() -> OffsetDateTime.now(ZoneId.systemDefault()).truncatedTo(ChronoUnit.HOURS).minusDays(2).minusHours(5).format(ISO_OFFSET_DATE_TIME),
                () -> args(CommonArguments.DAYS_ARG, "2", CommonArguments.HOURS_ARG, "5").getStartDate());
    }

    @Test
    void endDateIsTheStartOfTomorrow() {
        assertDate(() -> OffsetDateTime.now(ZoneId.systemDefault()).truncatedTo(ChronoUnit.DAYS).plusDays(1).format(ISO_OFFSET_DATE_TIME),
                () -> args(CommonArguments.DAYS_ARG, "9").getEndDate());
    }

    @Test
    void toStringAndHashCodeDelegateToTheGenerator() {
        assertEquals("zendesk-stats-config", args().toString());
        assertEquals(17, args().hashCode());
    }
}
