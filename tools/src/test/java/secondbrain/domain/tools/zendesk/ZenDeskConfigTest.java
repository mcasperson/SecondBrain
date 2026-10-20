package secondbrain.domain.tools.zendesk;

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
import secondbrain.domain.date.DateParser;
import secondbrain.domain.encryption.Encryptor;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.objects.ToStringGenerator;
import secondbrain.domain.sanitize.SanitizeArgument;
import secondbrain.domain.test.TestArgs;
import secondbrain.domain.test.TestConfigUtil;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tools.CommonArguments;
import secondbrain.domain.tools.keyword.Keywords;
import secondbrain.domain.validate.ValidateInputs;
import secondbrain.domain.validate.ValidateStringBlank;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ZenDeskConfig.LocalArguments}.
 */
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(ZenDeskConfig.class)
@AddBeanClasses(ArgsAccessorSimple.class)
@AddBeanClasses(ValidateStringBlank.class)
// Keywords is replaced by a mock from a producer method
@ExcludeBeanClasses(Keywords.class)
class ZenDeskConfigTest {
    private final Keywords keywordsTool = mock(Keywords.class);

    @Inject
    private ZenDeskConfig config;

    @Produces
    Keywords produceKeywords() {
        return keywordsTool;
    }

    @Produces
    ValidateInputs produceValidateInputs() {
        return new BracketingValidateInputs();
    }

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
    @Identifier("sanitizeEmail")
    SanitizeArgument produceSanitizeEmail() {
        return (argument, document) -> argument == null ? "" : argument.trim();
    }

    @Produces
    @Identifier("sanitizeOrganization")
    SanitizeArgument produceSanitizeOrganization() {
        return (argument, document) -> argument == null ? "" : argument.trim().toUpperCase();
    }

    @Produces
    ToStringGenerator produceToStringGenerator() {
        return TestArgs.toStringGenerator("zendesk-config", 42);
    }

    @BeforeEach
    void registerConfig() {
        TestConfigUtil.registerConfig(Map.of());
    }

    /**
     * Registers a single configuration value. Must be called before the config bean is first used, as CDI creates
     * it (and reads its configuration) lazily.
     */
    private void configure(final String key, final String value) {
        TestConfigUtil.registerConfig(Map.of(key, value));
    }

    private ZenDeskConfig.LocalArguments local(final List<ToolArgs> arguments, final String prompt, final Map<String, String> context) {
        return config.new LocalArguments(arguments, List.of(prompt), context);
    }

    private ZenDeskConfig.LocalArguments args(final String name, final String value) {
        return local(List.of(new ToolArgs(name, value, true)), "prompt", Map.of());
    }

    private ZenDeskConfig.LocalArguments none() {
        return local(List.of(), "prompt", Map.of());
    }

    @Test
    void configValueTakesPrecedenceOverContextAndArguments() {
        configure("sb.zendesk.organization", "fromConfig");

        final ZenDeskConfig.LocalArguments local = local(
                List.of(new ToolArgs(ZenDeskOrganization.ZENDESK_ORGANIZATION_ARG, "fromArgs", true)),
                "prompt",
                Map.of(ZenDeskOrganization.ZENDESK_ORGANIZATION_ARG, "fromContext"));

        assertEquals("fromConfig", local.getRawOrganization());
    }

    @Test
    void contextValueTakesPrecedenceOverArguments() {
        final ZenDeskConfig.LocalArguments local = local(
                List.of(new ToolArgs(ZenDeskOrganization.ZENDESK_ORGANIZATION_ARG, "fromArgs", true)),
                "prompt",
                Map.of(ZenDeskOrganization.ZENDESK_ORGANIZATION_ARG, "fromContext"));

        assertEquals("fromContext", local.getRawOrganization());
    }

    @Test
    void untrustedOrganizationIsValidatedAgainstThePrompt() {
        final ZenDeskConfig.LocalArguments local = local(
                List.of(new ToolArgs(ZenDeskOrganization.ZENDESK_ORGANIZATION_ARG, "llm guess", false)), "the prompt", Map.of());

        assertEquals("[llm guess|the prompt]", local.getRawOrganization());
    }

    @Test
    void organizationIsSanitized() {
        assertEquals("ACME", args(ZenDeskOrganization.ZENDESK_ORGANIZATION_ARG, " acme ").getOrganization());
    }

    @Test
    void emailOrganizationWithoutRecipientIsTreatedAsRecipient() {
        final ZenDeskConfig.LocalArguments local = args(ZenDeskOrganization.ZENDESK_ORGANIZATION_ARG, " support@acme.com ");

        assertEquals("support@acme.com", local.getRecipient());
    }

    @Test
    void explicitRecipientIsKeptAlongsideOrganization() {
        final ZenDeskConfig.LocalArguments local = local(List.of(
                new ToolArgs(ZenDeskOrganization.ZENDESK_ORGANIZATION_ARG, "acme", true),
                new ToolArgs(ZenDeskOrganization.RECIPIENT_ARG, " help@acme.com ", true)), "prompt", Map.of());

        assertEquals("ACME", local.getOrganization());
        assertEquals("help@acme.com", local.getRecipient());
    }

    @Test
    void excludedOrganizationsAreTrimmedAndSplit() {
        final List<String> excluded = args(ZenDeskOrganization.EXCLUDE_ORGANIZATION_ARG, " a, b ,,").getExcludedOrganization();

        assertEquals(List.of("a", "b"), excluded.stream().filter(s -> !s.isBlank()).toList());
    }

    @Test
    void excludedSubmittersAreTrimmedAndSplit() {
        assertEquals(List.of("s1", "s2"), args(ZenDeskOrganization.EXCLUDE_SUBMITTERS_ARG, "s1 , s2,,").getExcludedSubmitters());
        assertEquals(List.of(), none().getExcludedSubmitters());
    }

    @ParameterizedTest
    @CsvSource({"12,12", "0,0", "-5,0", "abc,0"})
    void rawHoursAndDaysAreNonNegativeIntegers(final String value, final int expected) {
        assertEquals(expected, args(CommonArguments.HOURS_ARG, value).getRawHours());
        assertEquals(expected, args(CommonArguments.DAYS_ARG, value).getRawDays());
    }

    @Test
    void trustedHoursAndDaysAreUsedAsIs() {
        final ZenDeskConfig.LocalArguments local = local(List.of(
                new ToolArgs(CommonArguments.HOURS_ARG, "6", true),
                new ToolArgs(CommonArguments.DAYS_ARG, "2", true)), "no units mentioned", Map.of());

        assertEquals(6, local.getHours());
        assertEquals(2, local.getDays());
    }

    @Test
    void untrustedDaysAreIgnoredWhenThePromptNeverMentionsDays() {
        final ZenDeskConfig.LocalArguments local = local(List.of(
                new ToolArgs(CommonArguments.HOURS_ARG, "6", false),
                new ToolArgs(CommonArguments.DAYS_ARG, "2", false)), "tickets in the last 6 hours", Map.of());

        assertEquals(6, local.getHours());
        assertEquals(0, local.getDays());
    }

    @Test
    void untrustedDaysMistakenlyPassedAsHoursAreSwitched() {
        final ZenDeskConfig.LocalArguments local = local(List.of(
                new ToolArgs(CommonArguments.HOURS_ARG, "5", false),
                new ToolArgs(CommonArguments.DAYS_ARG, "0", false)), "tickets from the last 5 days", Map.of());

        assertEquals(5, local.getDays());
        assertEquals(0, local.getHours());
    }

    @Test
    void untrustedValuesAreTrustedWhenBothUnitsAreMentioned() {
        final ZenDeskConfig.LocalArguments local = local(List.of(
                new ToolArgs(CommonArguments.HOURS_ARG, "0", false),
                new ToolArgs(CommonArguments.DAYS_ARG, "3", false)), "3 days and 0 hours", Map.of());

        assertEquals(3, local.getDays());
        assertEquals(0, local.getHours());
    }

    @Test
    void startAndEndPeriodsAreParsedAndNormalised() {
        final ZenDeskConfig.LocalArguments local = local(List.of(
                new ToolArgs(CommonArguments.START_DATE, "2024-03-01T10:15:30Z", true),
                new ToolArgs(CommonArguments.END_DATE, "2024-03-31T00:00:00+02:00", true)), "prompt", Map.of());

        assertEquals("2024-03-01T10:15:30Z", local.getStartPeriod());
        assertEquals("2024-03-31T00:00:00+02:00", local.getEndPeriod());
        assertEquals("", none().getStartPeriod());
        assertEquals("", none().getEndPeriod());
    }

    @Test
    void startDateDefaultsToOneDayAgoAtStartOfDay() {
        final String before = OffsetDateTime.now(ZoneId.systemDefault()).truncatedTo(ChronoUnit.DAYS).minusDays(1).format(ISO_OFFSET_DATE_TIME);
        final String actual = none().getStartDate();
        final String after = OffsetDateTime.now(ZoneId.systemDefault()).truncatedTo(ChronoUnit.DAYS).minusDays(1).format(ISO_OFFSET_DATE_TIME);

        assertTrue(actual.equals(before) || actual.equals(after), actual);
    }

    @Test
    void startDateSubtractsHoursFromTheStartOfTheCurrentHour() {
        final ZenDeskConfig.LocalArguments local = local(List.of(
                new ToolArgs(CommonArguments.HOURS_ARG, "5", true),
                new ToolArgs(CommonArguments.DAYS_ARG, "0", true)), "prompt", Map.of());
        final String before = OffsetDateTime.now(ZoneId.systemDefault()).truncatedTo(ChronoUnit.HOURS).minusHours(5).format(ISO_OFFSET_DATE_TIME);
        final String actual = local.getStartDate();
        final String after = OffsetDateTime.now(ZoneId.systemDefault()).truncatedTo(ChronoUnit.HOURS).minusHours(5).format(ISO_OFFSET_DATE_TIME);

        assertTrue(actual.equals(before) || actual.equals(after), actual);
    }

    @Test
    void endDateIsTheStartOfTomorrow() {
        final String before = OffsetDateTime.now(ZoneId.systemDefault()).truncatedTo(ChronoUnit.DAYS).plusDays(1).format(ISO_OFFSET_DATE_TIME);
        final String actual = none().getEndDate();
        final String after = OffsetDateTime.now(ZoneId.systemDefault()).truncatedTo(ChronoUnit.DAYS).plusDays(1).format(ISO_OFFSET_DATE_TIME);

        assertTrue(actual.equals(before) || actual.equals(after), actual);
    }

    @Test
    void filterByOrganizationDefaultsToFalse() {
        assertFalse(none().getZenDeskFilterByOrganization());
    }

    @Test
    void filterByOrganizationCanBeEnabledByArgument() {
        assertTrue(args(ZenDeskOrganization.ZENDESK_CONTEXT_FILTER_BY_ORGANIZATION_ARG, "true").getZenDeskFilterByOrganization());
    }

    @Test
    void filterByOrganizationConfigTakesPrecedenceOverTheArgument() {
        configure("sb.zendesk.filterbyorganization", "false");

        assertFalse(args(ZenDeskOrganization.ZENDESK_CONTEXT_FILTER_BY_ORGANIZATION_ARG, "true").getZenDeskFilterByOrganization());
    }

    @Test
    void filterByOrganizationCanBeEnabledInConfig() {
        configure("sb.zendesk.filterbyorganization", "true");

        assertTrue(none().getZenDeskFilterByOrganization());
    }

    @Test
    void secretTokenIsDecryptedFromContext() {
        assertEquals("tok", local(List.of(), "p", Map.of("zendesk_access_token", "enc(tok)")).getSecretToken());
    }

    @Test
    void plainTextTokenInContextIsUsedWhenItCannotBeDecrypted() {
        assertEquals("plain", local(List.of(), "p", Map.of("zendesk_access_token", "plain")).getSecretToken());
    }

    @Test
    void secretTokenFailsWhenMissingEverywhere() {
        assertThrows(InternalFailure.class, () -> none().getSecretToken());
    }

    @Test
    void secretTokenFallsBackToConfig() {
        configure("sb.zendesk.accesstoken", "configured");

        assertEquals("configured", none().getSecretToken());
    }

    @Test
    void userAndUrlFailWhenMissing() {
        assertThrows(InternalFailure.class, () -> none().getUser());
        assertThrows(InternalFailure.class, () -> none().getUrl());
    }

    @Test
    void userAndUrlFallBackToConfig() {
        TestConfigUtil.registerConfig(Map.of("sb.zendesk.user", "agent@acme.com", "sb.zendesk.url", "https://acme.zendesk.com"));

        assertEquals("agent@acme.com", none().getUser());
        assertEquals("https://acme.zendesk.com", none().getUrl());
        assertEquals("https://acme.zendesk.com", none().getZenDeskUrl());
    }

    @Test
    void userAndUrlAreDecryptedFromContext() {
        final ZenDeskConfig.LocalArguments local = local(List.of(), "p",
                Map.of("zendesk_user", "enc(me@acme.com)", "zendesk_url", "enc(https://x.zendesk.com)"));

        assertEquals("me@acme.com", local.getUser());
        assertEquals("https://x.zendesk.com", local.getUrl());
    }

    @Test
    void authHeaderIsBasicAuthWithApiTokenSuffix() {
        final ZenDeskConfig.LocalArguments local = local(List.of(), "p",
                Map.of("zendesk_user", "me@acme.com", "zendesk_access_token", "tok"));

        final String expected = "Basic " + Base64.getEncoder().encodeToString("me@acme.com/token:tok".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, local.getSecretAuthHeader());
    }

    @Test
    void secondaryCredentialsAreOptional() {
        assertEquals("", none().getSecretToken2());
        assertEquals("", none().getSecretAuthHeader2());

        final ZenDeskConfig.LocalArguments local = local(
                List.of(new ToolArgs(ZenDeskOrganization.ZENDESK_USER2_ARG, "two@acme.com", true),
                        new ToolArgs(ZenDeskOrganization.ZENDESK_URL2_ARG, "https://two.zendesk.com", true)),
                "p",
                Map.of("zendesk_access_token2", "enc(tok2)"));

        assertEquals("tok2", local.getSecretToken2());
        assertEquals("two@acme.com", local.getUser2());
        assertEquals("https://two.zendesk.com", local.getUrl2());
        assertEquals("Basic " + Base64.getEncoder().encodeToString("two@acme.com/token:tok2".getBytes(StandardCharsets.UTF_8)),
                local.getSecretAuthHeader2());
    }

    @Test
    void keywordsComeFromArgumentsUnlessAutoGenerated() {
        assertEquals(List.of("alpha", "beta"), args(CommonArguments.KEYWORDS_ARG, "alpha, beta").getKeywords());
        assertFalse(none().getAutoGenerateKeywords());
    }

    @Test
    void autoGeneratedKeywordsAreMergedWithSuppliedKeywords() {
        when(keywordsTool.getKeywords(anyMap(), anyList(), anyList())).thenReturn(List.of("gamma"));
        final ZenDeskConfig.LocalArguments local = local(List.of(
                new ToolArgs(CommonArguments.KEYWORDS_ARG, "alpha,beta", true),
                new ToolArgs(CommonArguments.AUTO_GENERATE_KEYWORDS_ARG, "true", true)), "prompt", Map.of());

        assertEquals(List.of("alpha", "beta", "gamma"), local.getKeywords());
    }

    @Test
    void numericSettingsUseDefaultsAndBounds() {
        assertEquals(Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH, none().getKeywordWindow());
        assertEquals(Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH, args(CommonArguments.KEYWORD_WINDOW_ARG, "wide").getKeywordWindow());
        assertEquals(50, args(CommonArguments.KEYWORD_WINDOW_ARG, "50").getKeywordWindow());

        assertEquals(60 * 60 * 24 * 90, none().getSearchTTL());
        assertEquals(0, args(ZenDeskOrganization.ZENDESK_HISTORY_TTL_ARG, "-10").getSearchTTL());

        assertEquals(10, none().getDefaultRating());
        assertEquals(0, args(CommonArguments.DEFAULT_RATING_ARG, "-1").getDefaultRating());

        assertEquals(0, none().getContextFilterMinimumRating());
        assertEquals(7, args(CommonArguments.CONTEXT_FILTER_MINIMUM_RATING_ARG, "7").getContextFilterMinimumRating());

        assertEquals(60 * 60 * 24, none().getCacheTtl());
        assertEquals(3600, args(ZenDeskOrganization.TTL_SECONDS, "3600").getCacheTtl());
    }

    @ParameterizedTest
    @CsvSource({"20,20", "100,100", "101,100", "500,100", "abc,100"})
    void maxTicketsIsCappedAtOneHundred(final String value, final int expected) {
        assertEquals(expected, args(ZenDeskOrganization.ZENDESK_MAX_TICKETS_ARG, value).getMaxTickets());
    }

    @Test
    void summarySettingsHaveSensibleDefaults() {
        assertTrue(none().getSummarizeTicket());
        assertFalse(args(CommonArguments.SUMMARIZE_DOCUMENT_ARG, "false").getSummarizeTicket());
        assertTrue(none().getDocumentSummaryPrompt().startsWith("Summarise the ticket in one paragraph."));
        assertEquals("custom", args(CommonArguments.SUMMARIZE_DOCUMENT_PROMPT_ARG, "custom").getDocumentSummaryPrompt());
    }

    @Test
    void filterAndFlagSettingsAreReadFromArguments() {
        assertEquals("is it urgent?", args(CommonArguments.CONTENT_RATING_QUESTION_ARG, "is it urgent?").getContextFilterQuestion());
        assertTrue(args(CommonArguments.FILTER_GREATER_THAN_ARG, "true").isContextFilterUpperLimit());
        assertFalse(none().isContextFilterUpperLimit());
        assertTrue(args(CommonArguments.SKIP_EMPTY_IN_LAST_DURATION, "true").isSkipEmptyInLastDuration());
        assertFalse(none().isSkipEmptyInLastDuration());
        assertTrue(args(CommonArguments.REQUIRE_COMPANY, "true").isRequireCompany());
        assertFalse(none().isRequireCompany());
    }

    @Test
    void hooksAndEntityAreReadFromArgumentsAndContext() {
        assertEquals("pre", args(CommonArguments.PREPROCESSOR_HOOKS_ARG, "pre").getPreprocessingHooks());
        assertEquals("init", args(CommonArguments.PREINITIALIZATION_HOOKS_ARG, "init").getPreinitializationHooks());
        assertEquals("post", args(CommonArguments.POSTINFERENCE_HOOKS_ARG, "post").getPostInferenceHooks());
        assertEquals("Acme", local(List.of(), "p", Map.of(CommonArguments.ENTITY_NAME_CONTEXT_ARG, "Acme")).getEntity());
    }

    @Test
    void toStringAndHashCodeDelegateToTheGenerator() {
        assertEquals("zendesk-config", none().toString());
        assertEquals(42, none().hashCode());
    }

    /**
     * Makes it visible when an untrusted value has been validated against the prompt.
     */
    private static final class BracketingValidateInputs implements ValidateInputs {
        @Override
        public String getCommaSeparatedList(final String input) {
            return "[" + input + "]";
        }

        @Override
        public String getCommaSeparatedList(final String prompt, final String input) {
            return "[" + input + "|" + prompt + "]";
        }
    }
}
