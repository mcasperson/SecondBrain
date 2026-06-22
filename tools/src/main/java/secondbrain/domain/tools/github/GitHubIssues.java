package secondbrain.domain.tools.github;

import io.smallrye.common.annotation.Identifier;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jooq.lambda.Seq;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.args.Argument;
import secondbrain.domain.config.LocalConfigFilteredItem;
import secondbrain.domain.config.LocalConfigSummarizer;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.context.SentenceSplitter;
import secondbrain.domain.context.SentenceVectorizer;
import secondbrain.domain.debug.DebugToolArgs;
import secondbrain.domain.encryption.Encryptor;
import secondbrain.domain.exceptionhandling.ExceptionMapping;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.hooks.HooksContainer;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.objects.ToStringGenerator;
import secondbrain.domain.processing.RagDocSummarizer;
import secondbrain.domain.processing.RatingMetadata;
import secondbrain.domain.tooldefs.*;
import secondbrain.domain.tools.CommonArguments;
import secondbrain.domain.validate.ValidateString;
import secondbrain.infrastructure.githubissues.GitHubIssuesClient;
import secondbrain.infrastructure.githubissues.api.GitHubIssue;
import secondbrain.infrastructure.llm.LlmClient;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME;

@ApplicationScoped
public class GitHubIssues implements Tool<Void> {
    public static final String GITHUB_ORGANIZATION_ARG = "githubOrganization";
    public static final String GITHUB_REPO_ARG = "githubRepo";
    public static final String GITHUB_ISSUE_LABELS_ARG = "githubIssueLabels";
    public static final String GITHUB_ISSUE_STATE_ARG = "githubIssueState";
    private static final String INSTRUCTIONS = """
            You are an expert in reading GitHub issues diffs.
            You are given a question and a list of summaries of GitHub Issues.
            You must assume the information required to answer the question is present in the Git Issues.
            You must answer the question based on the GitHub Issues provided.
            You must consider every Git Issue when providing the answer.
            You will be penalized for suggesting manual steps to generate the answer.
            You will be penalized for responding that you don't have access to real-time data or repositories.
            If there are no GitHub Issues, you must indicate that in the answer.
            """;
    @Inject
    private GitHubIssueConfig config;

    @Inject
    @Preferred
    private GitHubIssuesClient gitHubIssuesClient;

    @Inject
    private SentenceSplitter sentenceSplitter;

    @Inject
    private SentenceVectorizer sentenceVectorizer;

    @Inject
    private DebugToolArgs debugToolArgs;

    @Inject
    @Preferred
    private LlmClient llmClient;

    @Inject
    private Logger logger;

    @Inject
    @Preferred
    private RatingMetadata ratingMetadata;

    @Inject
    private ExceptionMapping exceptionMapping;

    @Inject
    private HooksContainer hooksContainer;

    @Inject
    @Preferred
    private RagDocSummarizer ragDocSummarizer;

    @Override
    public String getName() {
        return GitHubIssues.class.getSimpleName();
    }

    @Override
    public String getDescription() {
        return "Returns the details of a GitHub issue.";
    }

    @Override
    public List<ToolArguments> getArguments() {
        return List.of();
    }

    @Override
    public List<RagDocumentContext<Void>> getContext(
            final Map<String, String> environmentSettings,
            final List<String> prompts,
            final List<ToolArgs> arguments) {
        final String prompt = prompts.isEmpty() ? "" : prompts.getFirst();
        return Try.of(() -> getContextPrivate(environmentSettings, prompt, arguments))
                .onFailure(ex -> logger.warning("Failed to get context for " + getName() + ": " + ExceptionUtils.getRootCauseMessage(ex)))
                .getOrElse(List::of);
    }

    private List<RagDocumentContext<Void>> getContextPrivate(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {
        final GitHubIssueConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, List.of(prompt), environmentSettings);

        // Get preinitialization hooks before ragdocs
        final List<RagDocumentContext<GitHubIssue>> preinitHooks = Seq.seq(hooksContainer.getMatchingPreProcessorHooks(parsedArgs.getPreinitializationHooks()))
                .foldLeft(List.of(), (docs, hook) -> hook.process(getName(), docs));

        final List<RagDocumentContext<GitHubIssue>> ragDocs = Try
                .of(() -> gitHubIssuesClient.getIssues(
                        parsedArgs.getSecretGitHubAccessToken(),
                        parsedArgs.getGitHubOrganization(),
                        parsedArgs.getGitHubRepo(),
                        parsedArgs.getStartDate(),
                        parsedArgs.getEndDate(),
                        parsedArgs.getIssueLabels(),
                        parsedArgs.getIssueState()))
                .map(issues -> convertIssueToRagDoc(issues, parsedArgs, environmentSettings))
                .map(ragDocs1 -> updateMetadata(ragDocs1, environmentSettings, parsedArgs))
                .map(ragDocs1 -> parsedArgs.getSummarizeIssue()
                        ? ragDocSummarizer.getDocumentSummary(
                        getName(),
                        getContextLabel(),
                        "GitHubIssue",
                        ragDocs1,
                        environmentSettings,
                        parsedArgs)
                        : ragDocs1)
                .get();

        // Combine preinitialization hooks with ragDocs
        final List<RagDocumentContext<GitHubIssue>> combinedDocs = Stream.concat(preinitHooks.stream(), ragDocs.stream()).toList();

        // Apply preprocessing hooks
        return Seq.seq(hooksContainer.getMatchingPreProcessorHooks(parsedArgs.getPreprocessingHooks()))
                .foldLeft(combinedDocs, (docs, hook) -> hook.process(getName(), docs))
                .stream()
                .map(RagDocumentContext::convertToRagDocumentContextVoid)
                .toList();
    }

    @Override
    public int contextHashCode(final Map<String, String> environmentSettings, final List<String> prompts, final List<ToolArgs> arguments) {
        final GitHubIssueConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompts, environmentSettings);
        return 31 * parsedArgs.hashCode() + prompts.hashCode();
    }

    private List<RagDocumentContext<GitHubIssue>> convertIssueToRagDoc(
            final List<GitHubIssue> issues,
            final GitHubIssueConfig.LocalArguments parsedArgs,
            final Map<String, String> environmentSettings) {

        return issues
                .stream()
                .map(issue -> new RagDocumentContext<GitHubIssue>(
                        getName(),
                        getContextLabel(),
                        issue.body(),
                        sentenceVectorizer.vectorize(sentenceSplitter.splitDocument(issue.body(), 10)),
                        issue.getNumber().toString(),
                        issue,
                        "[" + parsedArgs.getGitHubOrganization() + "/" + parsedArgs.getGitHubRepo() + " " + issue.getNumber() + "](" + issue.htmlUrl() + ")"))
                .toList();
    }

    @Override
    public RagMultiDocumentContext<Void> call(final Map<String, String> environmentSettings, final List<String> prompts, final List<ToolArgs> arguments) {
        final String debugArgs = debugToolArgs.debugArgs(arguments);

        final GitHubIssueConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompts, environmentSettings);

        final List<RagDocumentContext<Void>> contextList = getContext(environmentSettings, prompts, arguments);

        final Try<RagMultiDocumentContext<Void>> result = Try
                .of(() -> contextList)
                .map(ragDocs -> new RagMultiDocumentContext<>(
                        prompts,
                        INSTRUCTIONS,
                        ragDocs,
                        debugArgs))
                .map(ragDoc -> llmClient.callWithCache(
                        ragDoc,
                        environmentSettings,
                        getName()));

        final RagMultiDocumentContext<Void> mappedResult = exceptionMapping.map(result).get();

        // Apply postinference hooks
        return Seq.seq(hooksContainer.getMatchingPostInferenceHooks(parsedArgs.getPostInferenceHooks()))
                .foldLeft(mappedResult, (docs, hook) -> hook.process(getName(), docs));
    }

    @Override
    public String getContextLabel() {
        return "GitHub Issue";
    }

    private List<RagDocumentContext<GitHubIssue>> updateMetadata(
            final List<RagDocumentContext<GitHubIssue>> issues,
            final Map<String, String> environmentSettings,
            final GitHubIssueConfig.LocalArguments parsedArgs) {
        return issues.stream()
                .map(issue -> ratingMetadata.getMetadata(getName(), environmentSettings, issue, parsedArgs)
                        .map(results -> issue
                                .addMetadata(results.getMetadata())
                                .addIntermediateResults(results.getIntermediateResults()))
                        .orElse(issue)
                )
                .toList();
    }

}

@ApplicationScoped
class GitHubIssueConfig {
    private static final int DEFAULT_RATING = 10;

    @Inject
    @ConfigProperty(name = "sb.githubissue.accessToken")
    private Optional<String> configAccessToken;

    @Inject
    @ConfigProperty(name = "sb.githubissue.organization")
    private Optional<String> configOrganization;

    @Inject
    @ConfigProperty(name = "sb.githubissue.repo")
    private Optional<String> configRepo;

    @Inject
    @ConfigProperty(name = "sb.githubissue.issueLabels")
    private Optional<String> configIssueLabels;

    @Inject
    @ConfigProperty(name = "sb.githubissue.issuestate")
    private Optional<String> configIssueState;

    @Inject
    @ConfigProperty(name = "sb.githubissue.contextFilterQuestion")
    private Optional<String> configContextFilterQuestion;

    @Inject
    @ConfigProperty(name = "sb.githubissue.contextFilterMinimumRating")
    private Optional<String> configContextFilterMinimumRating;

    @Inject
    @ConfigProperty(name = "sb.githubissue.contextFilterDefaultRating")
    private Optional<String> configContextFilterDefaultRating;

    @Inject
    @ConfigProperty(name = "sb.githubissue.issueSummaryPrompt")
    private Optional<String> configIssueSummaryPrompt;

    @Inject
    @ConfigProperty(name = "sb.githubissue.summarizeIssue")
    private Optional<String> configSummarizeIssue;

    @Inject
    @ConfigProperty(name = "sb.githubissue.startDate")
    private Optional<String> configStartDate;

    @Inject
    @ConfigProperty(name = "sb.githubissue.endDate")
    private Optional<String> configEndDate;

    @Inject
    @ConfigProperty(name = "sb.githubissue.days")
    private Optional<String> configDays;

    @Inject
    @ConfigProperty(name = "sb.githubissue.preprocessorHooks", defaultValue = "")
    private Optional<String> configPreprocessorHooks;

    @Inject
    @ConfigProperty(name = "sb.githubissue.preinitializationHooks", defaultValue = "")
    private Optional<String> configPreinitializationHooks;

    @Inject
    @ConfigProperty(name = "sb.githubissue.postinferenceHooks", defaultValue = "")
    private Optional<String> configPostInferenceHooks;

    @Inject
    private ArgsAccessor argsAccessor;

    @Inject
    @Identifier("AES")
    private Encryptor textEncryptor;

    @Inject
    private ValidateString validateString;

    @Inject
    private ToStringGenerator toStringGenerator;

    public Optional<String> getConfigAccessToken() {
        return configAccessToken;
    }

    public Optional<String> getConfigOrganization() {
        return configOrganization;
    }

    public Optional<String> getConfigRepo() {
        return configRepo;
    }

    public Optional<String> getConfigIssueLabels() {
        return configIssueLabels;
    }

    public Optional<String> getConfigIssueState() {
        return configIssueState;
    }

    public Optional<String> getConfigContextFilterQuestion() {
        return configContextFilterQuestion;
    }

    public Optional<String> getConfigContextFilterMinimumRating() {
        return configContextFilterMinimumRating;
    }

    public Optional<String> getConfigContextFilterDefaultRating() {
        return configContextFilterDefaultRating;
    }

    public Optional<String> getConfigIssueSummaryPrompt() {
        return configIssueSummaryPrompt;
    }

    public Optional<String> getConfigSummarizeIssue() {
        return configSummarizeIssue;
    }

    public Optional<String> getConfigStartDate() {
        return configStartDate;
    }

    public Optional<String> getConfigEndDate() {
        return configEndDate;
    }

    public Optional<String> getConfigDays() {
        return configDays;
    }

    public Optional<String> getConfigPreprocessorHooks() {
        return configPreprocessorHooks;
    }

    public Optional<String> getConfigPreinitializationHooks() {
        return configPreinitializationHooks;
    }

    public Optional<String> getConfigPostInferenceHooks() {
        return configPostInferenceHooks;
    }

    public ArgsAccessor getArgsAccessor() {
        return argsAccessor;
    }

    public Encryptor getTextEncryptor() {
        return textEncryptor;
    }

    public ValidateString getValidateString() {
        return validateString;
    }

    public ToStringGenerator getToStringGenerator() {
        return toStringGenerator;
    }

    public class LocalArguments implements LocalConfigFilteredItem, LocalConfigSummarizer {
        private final List<ToolArgs> arguments;
        private final List<String> prompts;
        private final Map<String, String> context;

        public LocalArguments(final List<ToolArgs> arguments, final List<String> prompts, final Map<String, String> context) {
            this.arguments = List.copyOf(arguments);
            this.prompts = List.copyOf(prompts);
            this.context = Map.copyOf(context);
        }

        @Override
        public String toString() {
            return getToStringGenerator().generateGetterConfig(this);
        }

        @Override
        public int hashCode() {
            return getToStringGenerator().generateHashGetterConfig(this);
        }

        @SuppressWarnings("NullAway")
        public String getSecretGitHubAccessToken() {
            final Try<String> token = Try.of(() -> getTextEncryptor().decrypt(context.get("github_access_token")))
                    .recover(e -> context.get("github_access_token"))
                    .mapTry(getValidateString()::throwIfBlank)
                    .recoverWith(e -> Try.of(() -> getConfigAccessToken().get()));

            if (token.isFailure() || StringUtils.isBlank(token.get())) {
                throw new InternalFailure("Failed to get GitHub access token");
            }

            return token.get();
        }

        public String getGitHubOrganization() {
            return getArgsAccessor().getArgument(
                    getConfigOrganization()::get,
                    arguments,
                    context,
                    GitHubIssues.GITHUB_ORGANIZATION_ARG,
                    GitHubIssues.GITHUB_ORGANIZATION_ARG,
                    "").getSafeValue();
        }

        public String getGitHubRepo() {
            return getArgsAccessor().getArgument(
                    getConfigRepo()::get,
                    arguments,
                    context,
                    GitHubIssues.GITHUB_REPO_ARG,
                    GitHubIssues.GITHUB_REPO_ARG,
                    "").getSafeValue();
        }

        public List<String> getIssueLabels() {
            return getArgsAccessor().getArgumentList(
                            getConfigIssueLabels()::get,
                            arguments,
                            context,
                            GitHubIssues.GITHUB_ISSUE_LABELS_ARG,
                            GitHubIssues.GITHUB_ISSUE_LABELS_ARG,
                            "")
                    .stream()
                    .map(Argument::value)
                    .toList();
        }

        public String getIssueState() {
            return getArgsAccessor().getArgument(
                            getConfigIssueState()::get,
                            arguments,
                            context,
                            GitHubIssues.GITHUB_ISSUE_STATE_ARG,
                            GitHubIssues.GITHUB_ISSUE_STATE_ARG,
                            "")
                    .getSafeValue();
        }

        @Override
        public Integer getDefaultRating() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigContextFilterDefaultRating()::get,
                    arguments,
                    context,
                    CommonArguments.DEFAULT_RATING_ARG,
                    CommonArguments.DEFAULT_RATING_ARG,
                    DEFAULT_RATING + "");

            return Math.max(0, org.apache.commons.lang3.math.NumberUtils.toInt(argument.getSafeValue(), DEFAULT_RATING));
        }

        @Override
        public String getContextFilterQuestion() {
            return getArgsAccessor().getArgument(
                            getConfigContextFilterQuestion()::get,
                            arguments,
                            context,
                            CommonArguments.CONTENT_RATING_QUESTION_ARG,
                            CommonArguments.CONTENT_RATING_QUESTION_ARG,
                            "")
                    .getSafeValue();
        }

        public Integer getContextFilterMinimumRating() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigContextFilterMinimumRating()::get,
                    arguments,
                    context,
                    CommonArguments.CONTEXT_FILTER_MINIMUM_RATING_ARG,
                    CommonArguments.CONTEXT_FILTER_MINIMUM_RATING_ARG,
                    "0");

            return org.apache.commons.lang.math.NumberUtils.toInt(argument.getSafeValue(), 0);
        }

        public String getDocumentSummaryPrompt() {
            return getArgsAccessor()
                    .getArgument(
                            getConfigIssueSummaryPrompt()::get,
                            arguments,
                            context,
                            CommonArguments.SUMMARIZE_DOCUMENT_PROMPT_ARG,
                            CommonArguments.SUMMARIZE_DOCUMENT_PROMPT_ARG,
                            "Summarise the GitHub issue in three paragraphs")
                    .getSafeValue();
        }

        public boolean getSummarizeIssue() {
            final String value = getArgsAccessor().getArgument(
                    getConfigSummarizeIssue()::get,
                    arguments,
                    context,
                    CommonArguments.SUMMARIZE_DOCUMENT_ARG,
                    CommonArguments.SUMMARIZE_DOCUMENT_ARG,
                    "").getSafeValue();

            return BooleanUtils.toBoolean(value);
        }

        @Nullable
        public String getStartDate() {
            final String configuredDate = getArgsAccessor().getArgument(
                    getConfigStartDate()::get,
                    arguments,
                    context,
                    CommonArguments.START_DATE,
                    CommonArguments.START_DATE,
                    "").getSafeValue();

            if (StringUtils.isNotBlank(configuredDate)) {
                return configuredDate;
            }

            if (getDays() == 0) {
                return null;
            }

            return OffsetDateTime.now(ZoneId.systemDefault())
                    .truncatedTo(ChronoUnit.DAYS)
                    .minusDays(getDays())
                    .format(ISO_OFFSET_DATE_TIME);
        }

        @Nullable
        public String getEndDate() {
            final String configuredDate = getArgsAccessor().getArgument(
                    getConfigEndDate()::get,
                    arguments,
                    context,
                    CommonArguments.END_DATE,
                    CommonArguments.END_DATE,
                    "").getSafeValue();

            if (StringUtils.isNotBlank(configuredDate)) {
                return configuredDate;
            }

            if (getDays() == 0) {
                return null;
            }

            return OffsetDateTime.now(ZoneId.systemDefault())
                    .truncatedTo(ChronoUnit.DAYS)
                    .plusDays(1)
                    .format(ISO_OFFSET_DATE_TIME);
        }

        public int getDays() {
            final String stringValue = getArgsAccessor().getArgument(
                    getConfigDays()::get,
                    arguments,
                    context,
                    CommonArguments.DAYS_ARG,
                    CommonArguments.DAYS_ARG,
                    "0").getSafeValue();

            return Try.of(() -> Integer.parseInt(stringValue))
                    .recover(throwable -> 0)
                    .map(i -> Math.max(0, i))
                    .get();
        }

        public String getPreprocessingHooks() {
            return getArgsAccessor().getArgument(
                    getConfigPreprocessorHooks()::get,
                    arguments,
                    context,
                    CommonArguments.PREPROCESSOR_HOOKS_ARG,
                    CommonArguments.PREPROCESSOR_HOOKS_ARG,
                    "").getSafeValue();
        }

        public String getPreinitializationHooks() {
            return getArgsAccessor().getArgument(
                    getConfigPreinitializationHooks()::get,
                    arguments,
                    context,
                    CommonArguments.PREINITIALIZATION_HOOKS_ARG,
                    CommonArguments.PREINITIALIZATION_HOOKS_ARG,
                    "").getSafeValue();
        }

        public String getPostInferenceHooks() {
            return getArgsAccessor().getArgument(
                    getConfigPostInferenceHooks()::get,
                    arguments,
                    context,
                    CommonArguments.POSTINFERENCE_HOOKS_ARG,
                    CommonArguments.POSTINFERENCE_HOOKS_ARG,
                    "").getSafeValue();
        }
    }
}
