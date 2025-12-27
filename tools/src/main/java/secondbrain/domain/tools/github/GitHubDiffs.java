package secondbrain.domain.tools.github;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientBuilder;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jooq.lambda.Seq;
import secondbrain.domain.args.ArgsAccessor;
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
import secondbrain.domain.list.ListUtilsEx;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.tools.CommonArguments;
import secondbrain.domain.validate.ValidateString;
import secondbrain.infrastructure.github.GitHubClient;
import secondbrain.infrastructure.github.api.GitHubCommitAndDiff;
import secondbrain.infrastructure.llm.LlmClient;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * The GitHubDiffs tool provides a list of Git diffs and answers questions about them. It works by first summarizing
 * each diff individually and then combining the summaries into a single document. This overcomes a limitation of
 * smaller LLMs, which struggled to interpret a collection of diffs. Arguably larger LLMs could handle this task, but
 * I couldn't find a single LLM supported by Ollama that could process a large collection of diffs in a single prompt.
 */
@ApplicationScoped
public class GitHubDiffs implements Tool<GitHubCommitAndDiff> {
    public static final String GITHUB_DIFF_OWNER_ARG = "owner";
    public static final String GITHUB_DIFF_REPO_ARG = "repo";
    public static final String GITHUB_DIFF_BRANCH_ARG = "branch";
    public static final String GITHUB_DIFF_SHA_ARG = "sha";
    public static final String GITHUB_DIFF_SINCE_ARG = "since";
    public static final String GITHUB_DIFF_UNTIL_ARG = "until";
    public static final String GITHUB_DIFF_MAX_DIFFS_ARG = "maxDiffs";
    public static final String GITHUB_DIFF_SUMMARY_PROMPT_ARG = "githubIssueSummaryPrompt";
    public static final String GITHUB_DIFF_SUMMARIZE_ARG = "githubSummarizeDiff";
    private static final String INSTRUCTIONS = """
            You are an expert in reading Git diffs.
            You are given a question and a list of summaries of Git Diffs and their associated commit messages.
            You must assume the Git Diffs capture the changes to the Git repository mentioned in the question.
            You must assume the information required to answer the question is present in the Git Diffs or commit messages.
            You must answer the question based on the Git Diffs or commit messages provided.
            You must consider every Git Diff and commit message when providing the answer.
            When the user asks a question indicating that they want to know the changes in the repository, you must generate the answer based on the Git Diffs or commit messages.
            You will be penalized for suggesting manual steps to generate the answer.
            You will be penalized for responding that you don't have access to real-time data or repositories.
            If there are no Git Diffs or commit messages, you must indicate that in the answer.
            """;
    @Inject
    private GitHubDiffConfig config;

    @Inject
    private DebugToolArgs debugToolArgs;

    @Inject
    @Preferred
    private GitHubClient gitHubClient;

    @Inject
    @Preferred
    private LlmClient llmClient;

    @Inject
    private SentenceSplitter sentenceSplitter;

    @Inject
    private SentenceVectorizer sentenceVectorizer;

    @Inject
    private Logger logger;

    @Inject
    private ExceptionMapping exceptionMapping;

    @Inject
    private HooksContainer hooksContainer;

    @Override
    public String getName() {
        return GitHubDiffs.class.getSimpleName();
    }

    @Override
    public String getDescription() {
        return """
                Provides a list of Git diffs and answers questions about them.
                Example prompts include:
                * Given the diffs from owner "mcasperson" and repo "SecondBrain" on branch "main" between 2021-01-01T00:00Z and 2021-01-31T00:00Z, generate release notes with an introductory paragraph and then bullet points of significant changes.
                """;
    }

    @Override
    public String getContextLabel() {
        return "Git Diff";
    }

    @Override
    public List<ToolArguments> getArguments() {
        return List.of(
                new ToolArguments(GITHUB_DIFF_OWNER_ARG, "The github owner to check", "mcasperson"),
                new ToolArguments(GITHUB_DIFF_REPO_ARG, "The repository to check", "SecondBrain"),
                new ToolArguments(GITHUB_DIFF_BRANCH_ARG, "The branch to check", "main"),
                new ToolArguments(GITHUB_DIFF_SHA_ARG, "The git sha to check", ""),
                new ToolArguments(GITHUB_DIFF_SINCE_ARG, "The optional date to start checking from", ""),
                new ToolArguments(GITHUB_DIFF_UNTIL_ARG, "The optional date to stop checking at", ""),
                new ToolArguments(CommonArguments.DAYS_ARG, "The optional number of days worth of diffs to return", "0"),
                new ToolArguments(GITHUB_DIFF_MAX_DIFFS_ARG, "The optional number of diffs to return", "0"),
                new ToolArguments(GITHUB_DIFF_SUMMARIZE_ARG, "Set to true to first summarize each diff", "true"),
                new ToolArguments(GITHUB_DIFF_SUMMARY_PROMPT_ARG, "The prompt used to summarize the diff", "true")
        );
    }

    @Override
    public List<RagDocumentContext<GitHubCommitAndDiff>> getContext(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {

        final GitHubDiffConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        final String authHeader = "Bearer " + parsedArgs.getSecretToken();

        // Get preinitialization hooks before ragdocs
        final List<RagDocumentContext<GitHubCommitAndDiff>> preinitHooks = Seq.seq(hooksContainer.getMatchingPreProcessorHooks(parsedArgs.getPreinitializationHooks()))
                .foldLeft(List.of(), (docs, hook) -> hook.process(getName(), docs));

        // If we have a sha, then we are only interested in a single commit
        if (StringUtils.isNotBlank(parsedArgs.getSha())) {
            final List<RagDocumentContext<GitHubCommitAndDiff>> ragDocs = convertCommitsToDiffSummaries(
                    Try.withResources(ClientBuilder::newClient)
                            .of(client ->
                                    Try
                                            .of(() -> List.of(parsedArgs.getSha().split(",")))
                                            .map(commits -> gitHubClient.getCommits(client, parsedArgs.getOwner(), parsedArgs.getRepo(), commits, authHeader))
                                            .get())
                            .get(),
                    parsedArgs,
                    environmentSettings);

            // Combine preinitialization hooks with ragDocs
            final List<RagDocumentContext<GitHubCommitAndDiff>> combinedDocs = Stream.concat(preinitHooks.stream(), ragDocs.stream()).toList();

            // Apply preprocessing hooks
            return Seq.seq(hooksContainer.getMatchingPreProcessorHooks(parsedArgs.getPreprocessingHooks()))
                    .foldLeft(combinedDocs, (docs, hook) -> hook.process(getName(), docs));
        }

        // Otherwise, we are interested in a range of commits
        final List<RagDocumentContext<GitHubCommitAndDiff>> ragDocs = Try
                .of(() -> getCommits(
                        parsedArgs.getOwner(),
                        parsedArgs.getRepo(),
                        parsedArgs.getBranch(),
                        parsedArgs.getStartDate(),
                        parsedArgs.getEndDate(),
                        authHeader))
                // limit the number of changes
                .map(commitsResponse -> ListUtilsEx.safeSubList(
                        commitsResponse,
                        0,
                        parsedArgs.getMaxDiffs() > 0 ? parsedArgs.getMaxDiffs() : commitsResponse.size()))
                .map(commits -> convertCommitsToDiffSummaries(commits, parsedArgs, environmentSettings))
                .get();

        // Combine preinitialization hooks with ragDocs
        final List<RagDocumentContext<GitHubCommitAndDiff>> combinedDocs = Stream.concat(preinitHooks.stream(), ragDocs.stream()).toList();

        // Apply preprocessing hooks
        return Seq.seq(hooksContainer.getMatchingPreProcessorHooks(parsedArgs.getPreprocessingHooks()))
                .foldLeft(combinedDocs, (docs, hook) -> hook.process(getName(), docs));
    }

    @Override
    public RagMultiDocumentContext<GitHubCommitAndDiff> call(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {

        final String debugArgs = debugToolArgs.debugArgs(arguments);

        final GitHubDiffConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        final Try<RagMultiDocumentContext<GitHubCommitAndDiff>> result = Try
                .of(() -> getContext(environmentSettings, prompt, arguments))
                .map(ragDocs -> new RagMultiDocumentContext<>(prompt, INSTRUCTIONS, ragDocs, debugArgs))
                .map(ragDoc -> llmClient.callWithCache(ragDoc, environmentSettings, getName()));

        final RagMultiDocumentContext<GitHubCommitAndDiff> mappedResult = exceptionMapping.map(result).get();

        // Apply postinference hooks
        return Seq.seq(hooksContainer.getMatchingPostInferenceHooks(parsedArgs.getPostInferenceHooks()))
                .foldLeft(mappedResult, (docs, hook) -> hook.process(getName(), docs));
    }

    private List<RagDocumentContext<GitHubCommitAndDiff>> convertCommitsToDiffSummaries(
            final List<GitHubCommitAndDiff> commitsResponse,
            final GitHubDiffConfig.LocalArguments parsedArgs,
            final Map<String, String> environmentSettings) {

        return commitsResponse
                .stream()
                .map(commit -> getCommitSummary(commit, parsedArgs, environmentSettings))
                .toList();
    }

    /**
     * I could not get an LLM to provide a useful summary of a collection of Git Diffs. They would focus on the last diff
     * or hallucinate a bunch of random release notes. Instead, each diff is summarised individually and then combined
     * into a single document to be summarised again.
     */
    private RagDocumentContext<GitHubCommitAndDiff> getCommitSummary(final GitHubCommitAndDiff commit, final GitHubDiffConfig.LocalArguments parsedArgs, final Map<String, String> environmentSettings) {
        /*
             We can optionally summarize each commit as a way of reducing the context size of
             when there are a lot of large diffs.
         */
        final String summary = parsedArgs.getSummarizeDiff()
                ? Try.of(() -> getDiffSummary(commit.getMessageAndDiff(), parsedArgs, environmentSettings))
                .onFailure(throwable -> logger.warning("Failed to summarize diff for commit " + commit.commit().sha() + ": " + throwable.getMessage()))
                .getOrElse("Failed to summarize diff")
                : commit.getMessageAndDiff();

        return new RagDocumentContext<>(
                getName(),
                getContextLabel(),
                summary,
                sentenceVectorizer.vectorize(sentenceSplitter.splitDocument(summary, 10)),
                commit.commit().sha(),
                commit,
                "[" + parsedArgs.getOwner() + "/" + parsedArgs.getRepo() + " " + GitHubUrlParser.urlToCommitHash(commit.commit().html_url()) + "](" + commit.commit().html_url() + ")");
    }

    /**
     * Use the LLM to generate a plain text summary of the diff. This summary will be used to link the
     * final summary of all diffs to the changes in individual diffs.
     */
    private String getDiffSummary(final String diff, final GitHubDiffConfig.LocalArguments parsedArgs, final Map<String, String> environmentSettings) {
        final RagDocumentContext<String> context = new RagDocumentContext<>(
                getName(),
                getContextLabel(),
                diff,
                List.of()
        );

        return llmClient.callWithCache(
                new RagMultiDocumentContext<>(
                        parsedArgs.getDiffSummaryPrompt(),
                        "You are a helpful agent",
                        List.of(context)),
                environmentSettings,
                getName()
        ).getResponse();
    }

    private List<GitHubCommitAndDiff> getCommits(
            final String owner,
            final String repo,
            final String branch,
            final String since,
            final String until,
            final String authorization) {

        return Try.withResources(ClientBuilder::newClient)
                .of(client ->
                        gitHubClient.getCommitsInRange(client, owner, repo, branch, until, since, authorization)
                                .stream().map(commit -> new GitHubCommitAndDiff(
                                        commit,
                                        getCommitDiff(owner, repo, commit.sha(), authorization)))
                                .toList())
                .get();
    }

    private String getCommitDiff(
            final String owner,
            final String repo,
            final String sha,
            final String authorization) {

        return Try.withResources(ClientBuilder::newClient)
                .of(client -> gitHubClient.getDiff(client, owner, repo, sha, authorization)).get();
    }
}

/**
 * Exposes the arguments for the GitHubDiffs tool.
 */
@ApplicationScoped
class GitHubDiffConfig {
    private static final String DEFAULT_OWNER = "mcasperson";
    private static final String DEFAULT_REPO = "SecondBrain";
    private static final String DEFAULT_BRANCH = "main";
    private static final String DEFAULT_DURATION = "30";
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    @Inject
    @ConfigProperty(name = "sb.github.accesstoken")
    private Optional<String> configGithubAccessToken;

    @Inject
    @ConfigProperty(name = "sb.github.owner")
    private Optional<String> configGithubOwner;

    @Inject
    @ConfigProperty(name = "sb.github.repo")
    private Optional<String> configGithubRepo;

    @Inject
    @ConfigProperty(name = "sb.github.sha")
    private Optional<String> configGithubSha;

    @Inject
    @ConfigProperty(name = "sb.github.days")
    private Optional<String> configGithubDays;

    @Inject
    @ConfigProperty(name = "sb.github.maxdiffs")
    private Optional<String> configGithubMaxDiffs;

    @Inject
    @ConfigProperty(name = "sb.github.since")
    private Optional<String> configGithubSince;

    @Inject
    @ConfigProperty(name = "sb.github.until")
    private Optional<String> configGithubUntil;

    @Inject
    @ConfigProperty(name = "sb.github.branch")
    private Optional<String> configGithubBranch;

    @Inject
    @ConfigProperty(name = "sb.github.diffSummaryPrompt")
    private Optional<String> configDiffSummaryPrompt;

    @Inject
    @ConfigProperty(name = "sb.github.summarizeDiff")
    private Optional<String> configSummarizeDiff;

    @Inject
    @ConfigProperty(name = "sb.github.preprocessorHooks", defaultValue = "")
    private Optional<String> configPreprocessorHooks;

    @Inject
    @ConfigProperty(name = "sb.github.preinitializationHooks", defaultValue = "")
    private Optional<String> configPreinitializationHooks;

    @Inject
    @ConfigProperty(name = "sb.github.postinferenceHooks", defaultValue = "")
    private Optional<String> configPostInferenceHooks;

    @Inject
    private ArgsAccessor argsAccessor;

    @Inject
    private Encryptor textEncryptor;

    @Inject
    private ValidateString validateString;

    public Optional<String> getConfigGithubAccessToken() {
        return configGithubAccessToken;
    }

    public Optional<String> getConfigGithubOwner() {
        return configGithubOwner;
    }

    public Optional<String> getConfigGithubRepo() {
        return configGithubRepo;
    }

    public Optional<String> getConfigGithubSha() {
        return configGithubSha;
    }

    public Optional<String> getConfigGithubDays() {
        return configGithubDays;
    }

    public Optional<String> getConfigGithubMaxDiffs() {
        return configGithubMaxDiffs;
    }

    public Optional<String> getConfigGithubSince() {
        return configGithubSince;
    }

    public Optional<String> getConfigGithubUntil() {
        return configGithubUntil;
    }

    public Optional<String> getConfigGithubBranch() {
        return configGithubBranch;
    }

    public Optional<String> getConfigDiffSummaryPrompt() {
        return configDiffSummaryPrompt;
    }

    public Optional<String> getConfigSummarizeDiff() {
        return configSummarizeDiff;
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

    public class LocalArguments {
        private final List<ToolArgs> arguments;

        private final String prompt;

        private final Map<String, String> context;

        public LocalArguments(final List<ToolArgs> arguments, final String prompt, final Map<String, String> context) {
            this.arguments = arguments;
            this.prompt = prompt;
            this.context = context;
        }

        public int getDays() {
            final String stringValue = getArgsAccessor().getArgument(
                    getConfigGithubDays()::get,
                    arguments,
                    context,
                    CommonArguments.DAYS_ARG,
                    CommonArguments.DAYS_ARG,
                    DEFAULT_DURATION).getSafeValue();

            return Try.of(() -> stringValue)
                    .map(i -> Math.max(0, Integer.parseInt(i)))
                    .map(i -> i == 0 ? Integer.parseInt(DEFAULT_DURATION) : i)
                    .get();
        }

        public int getMaxDiffs() {
            final String stringValue = getArgsAccessor().getArgument(
                    getConfigGithubMaxDiffs()::get,
                    arguments,
                    context,
                    GitHubDiffs.GITHUB_DIFF_MAX_DIFFS_ARG,
                    GitHubDiffs.GITHUB_DIFF_MAX_DIFFS_ARG,
                    "0").getSafeValue();

            return Try.of(() -> stringValue)
                    .map(Integer::parseInt)
                    .recover(throwable -> 0)
                    .map(i -> Math.max(0, i))
                    .get();
        }

        public String getStartDate() {
            return getArgsAccessor().getArgument(
                    getConfigGithubSince()::get,
                    arguments,
                    context,
                    GitHubDiffs.GITHUB_DIFF_SINCE_ARG,
                    GitHubDiffs.GITHUB_DIFF_SINCE_ARG,
                    ZonedDateTime.now(ZoneOffset.UTC)
                            .truncatedTo(ChronoUnit.DAYS)
                            .minusDays(getDays())
                            .format(FORMATTER)).getSafeValue();
        }

        public String getEndDate() {
            return getArgsAccessor().getArgument(
                    getConfigGithubUntil()::get,
                    arguments,
                    context,
                    GitHubDiffs.GITHUB_DIFF_UNTIL_ARG,
                    GitHubDiffs.GITHUB_DIFF_UNTIL_ARG,
                    ZonedDateTime.now(ZoneOffset.UTC)
                            .plusDays(1)
                            .truncatedTo(ChronoUnit.DAYS)
                            .format(FORMATTER)).getSafeValue();
        }

        public String getOwner() {
            return getArgsAccessor().getArgument(
                    getConfigGithubOwner()::get,
                    arguments,
                    context,
                    GitHubDiffs.GITHUB_DIFF_OWNER_ARG,
                    GitHubDiffs.GITHUB_DIFF_OWNER_ARG,
                    DEFAULT_OWNER).getSafeValue();
        }

        public String getRepo() {
            return getArgsAccessor().getArgument(
                    getConfigGithubRepo()::get,
                    arguments,
                    context,
                    GitHubDiffs.GITHUB_DIFF_REPO_ARG,
                    GitHubDiffs.GITHUB_DIFF_REPO_ARG,
                    DEFAULT_REPO).getSafeValue();
        }

        public String getSha() {
            return getArgsAccessor().getArgument(
                    getConfigGithubSha()::get,
                    arguments,
                    context,
                    GitHubDiffs.GITHUB_DIFF_SHA_ARG,
                    GitHubDiffs.GITHUB_DIFF_SHA_ARG,
                    "").getSafeValue();
        }

        public String getBranch() {
            return getArgsAccessor().getArgument(
                    getConfigGithubBranch()::get,
                    arguments,
                    context,
                    GitHubDiffs.GITHUB_DIFF_BRANCH_ARG,
                    GitHubDiffs.GITHUB_DIFF_BRANCH_ARG,
                    DEFAULT_BRANCH).getSafeValue();
        }

        @SuppressWarnings("NullAway")
        public String getSecretToken() {
            return Try.of(() -> getTextEncryptor().decrypt(context.get("github_access_token")))
                    .recover(e -> context.get("github_access_token"))
                    .mapTry(getValidateString()::throwIfBlank)
                    .recoverWith(e -> Try.of(getConfigGithubAccessToken()::get))
                    .getOrElseThrow(ex -> new InternalFailure("Failed to get GitHub access token", ex));
        }

        public String getDiffSummaryPrompt() {
            return getArgsAccessor()
                    .getArgument(
                            getConfigDiffSummaryPrompt()::get,
                            arguments,
                            context,
                            GitHubDiffs.GITHUB_DIFF_SUMMARY_PROMPT_ARG,
                            GitHubDiffs.GITHUB_DIFF_SUMMARY_PROMPT_ARG,
                            "Summarise the GitHub issue in three paragraphs")
                    .getSafeValue();
        }

        public boolean getSummarizeDiff() {
            final String value = getArgsAccessor().getArgument(
                    getConfigSummarizeDiff()::get,
                    arguments,
                    context,
                    GitHubDiffs.GITHUB_DIFF_SUMMARIZE_ARG,
                    GitHubDiffs.GITHUB_DIFF_SUMMARIZE_ARG,
                    "").getSafeValue();

            return BooleanUtils.toBoolean(value);
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
