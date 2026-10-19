package secondbrain.domain.tools.github;

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
import secondbrain.domain.encryption.Encryptor;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.objects.ToStringGenerator;
import secondbrain.domain.test.TestArgs;
import secondbrain.domain.test.TestConfigUtil;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tools.CommonArguments;
import secondbrain.domain.validate.ValidateStringBlank;

import java.time.OffsetDateTime;
import java.time.ZoneId;
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
@AddBeanClasses(GitHubIssueConfig.class)
@AddBeanClasses(ArgsAccessorSimple.class)
@AddBeanClasses(ValidateStringBlank.class)
class GitHubIssueConfigTest {
    private final Map<String, String> settings = new HashMap<>();

    @Inject
    private GitHubIssueConfig config;

    @Produces
    @Identifier("AES")
    Encryptor produceEncryptor() {
        return TestArgs.encryptor();
    }

    @Produces
    ToStringGenerator produceToStringGenerator() {
        return TestArgs.toStringGenerator("github-issue-config", 23);
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

    private GitHubIssueConfig.LocalArguments context(final Map<String, String> context) {
        return config.new LocalArguments(List.of(), List.of("prompt"), context);
    }

    private GitHubIssueConfig.LocalArguments args(final String... nameValues) {
        final List<ToolArgs> arguments = new ArrayList<>();
        for (int i = 0; i < nameValues.length; i += 2) {
            arguments.add(new ToolArgs(nameValues[i], nameValues[i + 1], true));
        }
        return config.new LocalArguments(arguments, List.of("prompt"), Map.of());
    }

    private static void assertDate(final Supplier<String> expected, final Supplier<String> actual) {
        final String before = expected.get();
        final String value = actual.get();
        final String after = expected.get();
        assertTrue(value.equals(before) || value.equals(after), () -> "expected " + before + " but was " + value);
    }

    @Test
    void accessTokenIsDecryptedFromTheContextOrTakenAsPlainText() {
        assertEquals("gh-secret", context(Map.of("github_access_token", "enc(gh-secret)")).getSecretGitHubAccessToken());
        assertEquals("gh-plain", context(Map.of("github_access_token", "gh-plain")).getSecretGitHubAccessToken());
    }

    @Test
    void accessTokenFallsBackToConfigWhenTheContextIsBlankOrMissing() {
        configure("sb.githubissue.accessToken", "gh-config");

        assertEquals("gh-config", context(Map.of("github_access_token", " ")).getSecretGitHubAccessToken());
        assertEquals("gh-config", context(Map.of()).getSecretGitHubAccessToken());
        assertEquals("gh-context", context(Map.of("github_access_token", "gh-context")).getSecretGitHubAccessToken());
    }

    @Test
    void missingAccessTokenThrows() {
        final InternalFailure ex = assertThrows(InternalFailure.class, () -> context(Map.of()).getSecretGitHubAccessToken());

        assertEquals("Failed to get GitHub access token", ex.getMessage());
    }

    @Test
    void repositorySettingsComeFromArguments() {
        final GitHubIssueConfig.LocalArguments local = args(
                GitHubIssues.GITHUB_ORGANIZATION_ARG, "acme",
                GitHubIssues.GITHUB_REPO_ARG, "api",
                GitHubIssues.GITHUB_ISSUE_LABELS_ARG, " bug, help wanted ,,",
                GitHubIssues.GITHUB_ISSUE_STATE_ARG, "closed");

        assertEquals("acme", local.getGitHubOrganization());
        assertEquals("api", local.getGitHubRepo());
        assertEquals(List.of("bug", "help wanted"), local.getIssueLabels());
        assertEquals("closed", local.getIssueState());
    }

    @Test
    void repositorySettingsDefaultToEmpty() {
        final GitHubIssueConfig.LocalArguments local = args();

        assertEquals("", local.getGitHubOrganization());
        assertEquals("", local.getGitHubRepo());
        assertEquals(List.of(), local.getIssueLabels());
        assertEquals("", local.getIssueState());
    }

    @Test
    void configuredRepositorySettingsWin() {
        configure("sb.githubissue.organization", "config-org");
        configure("sb.githubissue.repo", "config-repo");
        configure("sb.githubissue.issueLabels", "config-label");
        configure("sb.githubissue.issuestate", "open");

        final GitHubIssueConfig.LocalArguments local = args(
                GitHubIssues.GITHUB_ORGANIZATION_ARG, "acme",
                GitHubIssues.GITHUB_REPO_ARG, "api",
                GitHubIssues.GITHUB_ISSUE_LABELS_ARG, "bug",
                GitHubIssues.GITHUB_ISSUE_STATE_ARG, "closed");

        assertEquals("config-org", local.getGitHubOrganization());
        assertEquals("config-repo", local.getGitHubRepo());
        assertEquals(List.of("config-label"), local.getIssueLabels());
        assertEquals("open", local.getIssueState());
    }

    @Test
    void contextOverridesArgumentsForRepositorySettings() {
        final GitHubIssueConfig.LocalArguments local = config.new LocalArguments(
                List.of(new ToolArgs(GitHubIssues.GITHUB_REPO_ARG, "arg-repo", true)),
                List.of("prompt"),
                Map.of(GitHubIssues.GITHUB_REPO_ARG, "context-repo"));

        assertEquals("context-repo", local.getGitHubRepo());
    }

    @ParameterizedTest
    @CsvSource({"4,4", "0,0", "-3,0", "high,10"})
    void defaultRatingIsNonNegativeAndDefaultsToTen(final String value, final int expected) {
        assertEquals(expected, args(CommonArguments.DEFAULT_RATING_ARG, value).getDefaultRating());
    }

    @ParameterizedTest
    @CsvSource({"6,6", "-1,-1", "high,0"})
    void minimumRatingIsParsedWithoutBounds(final String value, final int expected) {
        assertEquals(expected, args(CommonArguments.CONTEXT_FILTER_MINIMUM_RATING_ARG, value).getContextFilterMinimumRating());
    }

    @Test
    void ratingSettingsDefaults() {
        final GitHubIssueConfig.LocalArguments local = args();

        assertEquals(10, local.getDefaultRating());
        assertEquals(0, local.getContextFilterMinimumRating());
        assertEquals("", local.getContextFilterQuestion());
    }

    @Test
    void ratingSettingsFromConfigWin() {
        configure("sb.githubissue.contextFilterQuestion", "config?");
        configure("sb.githubissue.contextFilterMinimumRating", "7");
        configure("sb.githubissue.contextFilterDefaultRating", "3");

        final GitHubIssueConfig.LocalArguments local = args(
                CommonArguments.CONTENT_RATING_QUESTION_ARG, "arg?",
                CommonArguments.CONTEXT_FILTER_MINIMUM_RATING_ARG, "1",
                CommonArguments.DEFAULT_RATING_ARG, "9");

        assertEquals("config?", local.getContextFilterQuestion());
        assertEquals(7, local.getContextFilterMinimumRating());
        assertEquals(3, local.getDefaultRating());
    }

    @Test
    void summarySettings() {
        assertFalse(args().getSummarizeIssue());
        assertTrue(args(CommonArguments.SUMMARIZE_DOCUMENT_ARG, "true").getSummarizeIssue());
        assertFalse(args(CommonArguments.SUMMARIZE_DOCUMENT_ARG, "maybe").getSummarizeIssue());
        assertEquals("Summarise the GitHub issue in three paragraphs", args().getDocumentSummaryPrompt());
        assertEquals("One line", args(CommonArguments.SUMMARIZE_DOCUMENT_PROMPT_ARG, "One line").getDocumentSummaryPrompt());
    }

    @Test
    void summarySettingsFromConfigWin() {
        configure("sb.githubissue.summarizeIssue", "true");
        configure("sb.githubissue.issueSummaryPrompt", "Config prompt");

        final GitHubIssueConfig.LocalArguments local = args(
                CommonArguments.SUMMARIZE_DOCUMENT_ARG, "false",
                CommonArguments.SUMMARIZE_DOCUMENT_PROMPT_ARG, "Arg prompt");

        assertTrue(local.getSummarizeIssue());
        assertEquals("Config prompt", local.getDocumentSummaryPrompt());
    }

    @ParameterizedTest
    @CsvSource({"7,7", "0,0", "-4,0", "week,0"})
    void daysAreNonNegativeAndDefaultToZero(final String value, final int expected) {
        assertEquals(expected, args(CommonArguments.DAYS_ARG, value).getDays());
    }

    @Test
    void datesAreNullWithoutDaysOrExplicitDates() {
        assertEquals(0, args().getDays());
        assertNull(args().getStartDate());
        assertNull(args().getEndDate());
        assertNull(args(CommonArguments.DAYS_ARG, "-2").getStartDate());
    }

    @Test
    void explicitDatesAreReturnedVerbatim() {
        final GitHubIssueConfig.LocalArguments local = args(
                CommonArguments.START_DATE, "2024-01-01",
                CommonArguments.END_DATE, "2024-02-01",
                CommonArguments.DAYS_ARG, "5");

        assertEquals("2024-01-01", local.getStartDate());
        assertEquals("2024-02-01", local.getEndDate());
    }

    @Test
    void configuredDatesWin() {
        configure("sb.githubissue.startDate", "2023-01-01");
        configure("sb.githubissue.endDate", "2023-06-01");
        configure("sb.githubissue.days", "2");

        final GitHubIssueConfig.LocalArguments local = args(CommonArguments.START_DATE, "2024-01-01", CommonArguments.DAYS_ARG, "9");

        assertEquals("2023-01-01", local.getStartDate());
        assertEquals("2023-06-01", local.getEndDate());
        assertEquals(2, local.getDays());
    }

    @Test
    void datesAreCalculatedFromDays() {
        assertDate(() -> OffsetDateTime.now(ZoneId.systemDefault()).truncatedTo(ChronoUnit.DAYS).minusDays(3).format(ISO_OFFSET_DATE_TIME),
                () -> args(CommonArguments.DAYS_ARG, "3").getStartDate());
        assertDate(() -> OffsetDateTime.now(ZoneId.systemDefault()).truncatedTo(ChronoUnit.DAYS).plusDays(1).format(ISO_OFFSET_DATE_TIME),
                () -> args(CommonArguments.DAYS_ARG, "3").getEndDate());
    }

    @Test
    void hooksComeFromArgumentsAndConfig() {
        final GitHubIssueConfig.LocalArguments local = args(
                CommonArguments.PREPROCESSOR_HOOKS_ARG, "pre",
                CommonArguments.PREINITIALIZATION_HOOKS_ARG, "init",
                CommonArguments.POSTINFERENCE_HOOKS_ARG, "post");

        assertEquals("pre", local.getPreprocessingHooks());
        assertEquals("init", local.getPreinitializationHooks());
        assertEquals("post", local.getPostInferenceHooks());
        assertEquals("", args().getPreprocessingHooks());
        assertEquals("", args().getPreinitializationHooks());
        assertEquals("", args().getPostInferenceHooks());
    }

    @Test
    void configuredHooksWin() {
        configure("sb.githubissue.preprocessorHooks", "config-pre");
        configure("sb.githubissue.preinitializationHooks", "config-init");
        configure("sb.githubissue.postinferenceHooks", "config-post");

        final GitHubIssueConfig.LocalArguments local = args(
                CommonArguments.PREPROCESSOR_HOOKS_ARG, "pre",
                CommonArguments.PREINITIALIZATION_HOOKS_ARG, "init",
                CommonArguments.POSTINFERENCE_HOOKS_ARG, "post");

        assertEquals("config-pre", local.getPreprocessingHooks());
        assertEquals("config-init", local.getPreinitializationHooks());
        assertEquals("config-post", local.getPostInferenceHooks());
    }

    @Test
    void toStringAndHashCodeDelegateToTheGenerator() {
        assertEquals("github-issue-config", args().toString());
        assertEquals(23, args().hashCode());
    }
}
