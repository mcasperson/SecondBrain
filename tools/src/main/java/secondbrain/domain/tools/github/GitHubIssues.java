package secondbrain.domain.tools.github;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.args.Argument;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.context.SentenceSplitter;
import secondbrain.domain.context.SentenceVectorizer;
import secondbrain.domain.debug.DebugToolArgs;
import secondbrain.domain.encryption.Encryptor;
import secondbrain.domain.exceptionhandling.ExceptionMapping;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.tooldefs.*;
import secondbrain.domain.tools.rating.RatingTool;
import secondbrain.domain.validate.ValidateString;
import secondbrain.infrastructure.githubissues.GitHubIssuesClient;
import secondbrain.infrastructure.githubissues.api.GitHubIssue;
import secondbrain.infrastructure.llm.LlmClient;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME;

@ApplicationScoped
public class GitHubIssues implements Tool<GitHubIssue> {
    public static final String GITHUB_ISSUE_FILTER_RATING_META = "FilterRating";
    public static final String GITHUB_ISSUE_FILTER_QUESTION_ARG = "issueRatingQuestion";
    public static final String GITHUB_ISSUE_FILTER_MINIMUM_RATING_ARG = "issueFilterMinimumRating";
    public static final String GITHUB_ORGANIZATION_ARG = "githubOrganization";
    public static final String GITHUB_REPO_ARG = "githubRepo";
    public static final String GITHUB_ISSUE_LABELS_ARG = "githubIssueLabels";
    public static final String GITHUB_ISSUE_STATE_ARG = "githubIssueState";
    public static final String GITHUB_ISSUE_SUMMARY_PROMPT_ARG = "githubIssueSummaryPrompt";
    public static final String GITHUB_SUMMARIZE_ISSUE_ARG = "githubSummarizeIssue";
    public static final String GITHUB_START_DATE_ARG = "githubStartDate";
    public static final String GITHUB_END_DATE_ARG = "githubEndDate";
    public static final String GITHUB_DAYS_ARG = "githubDays";
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
    private RatingTool ratingTool;

    @Inject
    private ExceptionMapping exceptionMapping;

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
    public List<RagDocumentContext<GitHubIssue>> getContext(final Map<String, String> environmentSettings, final String prompt, final List<ToolArgs> arguments) {
        final GitHubIssueConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        return Try
                .of(() -> gitHubIssuesClient.getIssues(
                        parsedArgs.getGitHubAccessToken(),
                        parsedArgs.getGitHubOrganization(),
                        parsedArgs.getGitHubRepo(),
                        parsedArgs.getStartDate(),
                        parsedArgs.getEndDate(),
                        parsedArgs.getIssueLabels(),
                        parsedArgs.getIssueState()))
                .map(issues -> convertIssueToRagDoc(issues, parsedArgs, environmentSettings))
                .map(ragDocs -> updateMetadata(ragDocs, parsedArgs))
                .map(ragDocs -> parsedArgs.getSummarizeIssue() ? getSummary(ragDocs, environmentSettings, parsedArgs) : ragDocs)
                .get();
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
                        sentenceSplitter.splitDocument(issue.body(), 10)
                                .stream()
                                .map(sentenceVectorizer::vectorize)
                                .toList(),
                        issue.getNumber().toString(),
                        issue,
                        "[" + parsedArgs.getGitHubOrganization() + "/" + parsedArgs.getGitHubRepo() + " " + issue.getNumber() + "](" + issue.htmlUrl() + ")"))
                .toList();
    }

    @Override
    public RagMultiDocumentContext<GitHubIssue> call(final Map<String, String> environmentSettings, final String prompt, final List<ToolArgs> arguments) {
        final String debugArgs = debugToolArgs.debugArgs(arguments);

        final GitHubIssueConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        final Try<RagMultiDocumentContext<GitHubIssue>> result = Try
                .of(() -> getContext(environmentSettings, prompt, arguments))
                .map(ragDocs -> new RagMultiDocumentContext<>(
                        prompt,
                        INSTRUCTIONS,
                        ragDocs,
                        debugArgs))
                .map(ragDoc -> llmClient.callWithCache(
                        ragDoc,
                        environmentSettings,
                        getName()));

        return exceptionMapping.map(result).get();
    }

    @Override
    public String getContextLabel() {
        return "GitHub Issue";
    }

    private List<RagDocumentContext<GitHubIssue>> updateMetadata(
            final List<RagDocumentContext<GitHubIssue>> issues,
            final GitHubIssueConfig.LocalArguments parsedArgs) {
        return issues.stream()
                .map(issue -> issue.updateMetadata(getMetadata(issue, parsedArgs)))
                .toList();
    }

    private MetaObjectResults getMetadata(
            final RagDocumentContext<GitHubIssue> issue,
            final GitHubIssueConfig.LocalArguments parsedArgs) {

        final List<MetaObjectResult> metadata = new ArrayList<>();

        if (StringUtils.isNotBlank(parsedArgs.getContextFilterQuestion())) {
            final int filterRating = Try.of(() -> ratingTool.call(
                                    Map.of(RatingTool.RATING_DOCUMENT_CONTEXT_ARG, issue.document()),
                                    parsedArgs.getContextFilterQuestion(),
                                    List.of())
                            .getResponse())
                    .map(rating -> org.apache.commons.lang3.math.NumberUtils.toInt(rating.trim(), 0))
                    // Ratings are provided on a best effort basis, so we ignore any failures
                    .recover(ex -> 10)
                    .get();

            metadata.add(new MetaObjectResult(GITHUB_ISSUE_FILTER_RATING_META, filterRating));
        }

        return new MetaObjectResults(
                metadata,
                "GitHubIssue-" + issue.id() + ".json",
                issue.id());
    }

    private List<RagDocumentContext<GitHubIssue>> getSummary(final List<RagDocumentContext<GitHubIssue>> ragDocs, final Map<String, String> environmentSettings, final GitHubIssueConfig.LocalArguments parsedArgs) {
        return ragDocs.stream()
                .map(ragDoc -> getSummary(ragDoc, environmentSettings, parsedArgs))
                .toList();
    }

    private RagDocumentContext<GitHubIssue> getSummary(final RagDocumentContext<GitHubIssue> ragDoc, final Map<String, String> environmentSettings, final GitHubIssueConfig.LocalArguments parsedArgs) {
        logger.log(Level.INFO, "Summarising GitHub issues");

        final RagDocumentContext<String> context = new RagDocumentContext<>(
                getName(),
                getContextLabel(),
                ragDoc.document(),
                List.of()
        );

        final String response = llmClient.callWithCache(
                new RagMultiDocumentContext<>(
                        parsedArgs.getIssueSummaryPrompt(),
                        "You are a helpful agent",
                        List.of(context)),
                environmentSettings,
                getName()
        ).getResponse();

        return ragDoc.updateDocument(response)
                .addIntermediateResult(new IntermediateResult(
                        "Prompt: " + parsedArgs.getIssueSummaryPrompt() + "\n\n" + response,
                        "GitHubIssue-" + ragDoc.id() + "-" + DigestUtils.sha256Hex(parsedArgs.getIssueSummaryPrompt()) + ".txt"
                ));
    }
}

@ApplicationScoped
class GitHubIssueConfig {
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
    private ArgsAccessor argsAccessor;

    @Inject
    private Encryptor textEncryptor;

    @Inject
    private ValidateString validateString;

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

    public ArgsAccessor getArgsAccessor() {
        return argsAccessor;
    }

    public Encryptor getTextEncryptor() {
        return textEncryptor;
    }

    public ValidateString getValidateString() {
        return validateString;
    }

    public class LocalArguments {
        private final List<ToolArgs> arguments;
        private final String prompt;
        private final Map<String, String> context;

        public LocalArguments(final List<ToolArgs> arguments, final String prompt, final Map<String, String> context) {
            this.arguments = arguments;
            this.prompt = prompt;
            this.context = context;
        }

        public String getGitHubAccessToken() {
            final Try<String> token = Try.of(() -> getTextEncryptor().decrypt(context.get("github_access_token")))
                    .recover(e -> context.get("github_access_token"))
                    .mapTry(getValidateString()::throwIfEmpty)
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
                    "").value();
        }

        public String getGitHubRepo() {
            return getArgsAccessor().getArgument(
                    getConfigRepo()::get,
                    arguments,
                    context,
                    GitHubIssues.GITHUB_REPO_ARG,
                    GitHubIssues.GITHUB_REPO_ARG,
                    "").value();
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
                    .value();
        }

        public String getContextFilterQuestion() {
            return getArgsAccessor().getArgument(
                            getConfigContextFilterQuestion()::get,
                            arguments,
                            context,
                            GitHubIssues.GITHUB_ISSUE_FILTER_QUESTION_ARG,
                            GitHubIssues.GITHUB_ISSUE_FILTER_QUESTION_ARG,
                            "")
                    .value();
        }

        public Integer getContextFilterMinimumRating() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigContextFilterMinimumRating()::get,
                    arguments,
                    context,
                    GitHubIssues.GITHUB_ISSUE_FILTER_MINIMUM_RATING_ARG,
                    GitHubIssues.GITHUB_ISSUE_FILTER_MINIMUM_RATING_ARG,
                    "0");

            return org.apache.commons.lang.math.NumberUtils.toInt(argument.value(), 0);
        }

        public String getIssueSummaryPrompt() {
            return getArgsAccessor()
                    .getArgument(
                            getConfigIssueSummaryPrompt()::get,
                            arguments,
                            context,
                            GitHubIssues.GITHUB_ISSUE_SUMMARY_PROMPT_ARG,
                            GitHubIssues.GITHUB_ISSUE_SUMMARY_PROMPT_ARG,
                            "Summarise the GitHub issue in three paragraphs")
                    .value();
        }

        public boolean getSummarizeIssue() {
            final String value = getArgsAccessor().getArgument(
                    getConfigSummarizeIssue()::get,
                    arguments,
                    context,
                    GitHubIssues.GITHUB_SUMMARIZE_ISSUE_ARG,
                    GitHubIssues.GITHUB_SUMMARIZE_ISSUE_ARG,
                    "").value();

            return BooleanUtils.toBoolean(value);
        }

        public String getStartDate() {
            final String configuredDate = getArgsAccessor().getArgument(
                    getConfigStartDate()::get,
                    arguments,
                    context,
                    GitHubIssues.GITHUB_START_DATE_ARG,
                    GitHubIssues.GITHUB_START_DATE_ARG,
                    "").value();

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

        public String getEndDate() {
            final String configuredDate = getArgsAccessor().getArgument(
                    getConfigEndDate()::get,
                    arguments,
                    context,
                    GitHubIssues.GITHUB_END_DATE_ARG,
                    GitHubIssues.GITHUB_END_DATE_ARG,
                    "").value();

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
                    GitHubIssues.GITHUB_DAYS_ARG,
                    GitHubIssues.GITHUB_DAYS_ARG,
                    "0").value();

            return Try.of(() -> Integer.parseInt(stringValue))
                    .recover(throwable -> 0)
                    .map(i -> Math.max(0, i))
                    .get();
        }
    }
}