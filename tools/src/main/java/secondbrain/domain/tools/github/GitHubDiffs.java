package secondbrain.domain.tools.github;

import io.vavr.API;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientBuilder;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.config.ModelConfig;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.context.SentenceSplitter;
import secondbrain.domain.context.SentenceVectorizer;
import secondbrain.domain.debug.DebugToolArgs;
import secondbrain.domain.encryption.Encryptor;
import secondbrain.domain.exceptions.EmptyString;
import secondbrain.domain.exceptions.ExternalFailure;
import secondbrain.domain.exceptions.FailedOllama;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.limit.ListLimiter;
import secondbrain.domain.list.ListUtilsEx;
import secondbrain.domain.prompt.PromptBuilderSelector;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.validate.ValidateString;
import secondbrain.infrastructure.github.GitHubClient;
import secondbrain.infrastructure.github.api.GitHubCommitAndDiff;
import secondbrain.infrastructure.llm.LlmClient;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.base.Predicates.instanceOf;

/**
 * The GitHubDiffs tool provides a list of Git diffs and answers questions about them. It works by first summarizing
 * each diff individually and then combining the summaries into a single document. This overcomes a limitation of
 * smaller LLMs, which struggled to interpret a collection of diffs. Arguably larger LLMs could handle this task, but
 * I couldn't find a single LLM supported by Ollama that could process a large collection of diffs in a single prompt.
 */
@ApplicationScoped
public class GitHubDiffs implements Tool<GitHubCommitAndDiff> {
    private static final String INSTRUCTIONS = """
            You are an expert in reading Git diffs.
            You are given a question and a list of summaries of Git Diffs.
            You must assume the Git Diffs capture the changes to the Git repository mentioned in the question.
            You must assume the information required to answer the question is present in the Git Diffs.
            You must answer the question based on the Git Diffs provided.
            You must consider every Git Diff when providing the answer.
            When the user asks a question indicating that they want to know the changes in the repository, you must generate the answer based on the Git Diffs.
            You will be penalized for suggesting manual steps to generate the answer.
            You will be penalized for responding that you don't have access to real-time data or repositories.
            If there are no Git Diffs, you must indicate that in the answer.
            The summary must include all classes, functions, and variables found in the Git Diff.
            """;
    private static final String DIFF_INSTRUCTIONS = """
            You are an expert in reading Git diffs.
            You are given a Git Diff.
            You will be penalized for including a header or title in the output.
            You will be penalized for including any markdown or HTML in the output.
            The summary must include all classes, functions, and variables found in the Git Diff.
            """;
    @Inject
    private ModelConfig modelConfig;

    @Inject
    private GitHubDiffConfig config;

    @Inject
    private DebugToolArgs debugToolArgs;

    @Inject
    @Preferred
    private GitHubClient gitHubClient;

    @Inject
    private LlmClient llmClient;

    @Inject
    private ListLimiter listLimiter;

    @Inject
    private PromptBuilderSelector promptBuilderSelector;

    @Inject
    private ValidateString validateString;

    @Inject
    private SentenceSplitter sentenceSplitter;

    @Inject
    private SentenceVectorizer sentenceVectorizer;

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
                new ToolArguments("owner", "The github owner to check", "mcasperson"),
                new ToolArguments("repo", "The repository to check", "SecondBrain"),
                new ToolArguments("branch", "The branch to check", "main"),
                new ToolArguments("sha", "The git sha to check", ""),
                new ToolArguments("since", "The optional date to start checking from", ""),
                new ToolArguments("until", "The optional date to stop checking at", ""),
                new ToolArguments("days", "The optional number of days worth of diffs to return", "0"),
                new ToolArguments("maxDiffs", "The optional number of diffs to return", "0"),
                new ToolArguments("summarizeIndividualDiffs", "Set to true to first summarize each diff", "true")
        );
    }

    @Override
    public List<RagDocumentContext<GitHubCommitAndDiff>> getContext(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {

        final GitHubDiffConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        final String authHeader = "Bearer " + parsedArgs.getToken();

        // If we have a sha, then we are only interested in a single commit
        if (StringUtils.isNotBlank(parsedArgs.getSha())) {
            return convertCommitsToDiffSummaries(
                    Try.withResources(ClientBuilder::newClient)
                            .of(client ->
                                    Try
                                            .of(() -> List.of(parsedArgs.getSha().split(",")))
                                            .map(commits -> gitHubClient.getCommits(client, parsedArgs.getOwner(), parsedArgs.getRepo(), commits, authHeader))
                                            .get())
                            .get(),
                    parsedArgs,
                    environmentSettings);
        }

        // Otherwise, we are interested in a range of commits
        return Try
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
                .map(list -> listLimiter.limitListContent(
                        list,
                        RagDocumentContext::document,
                        modelConfig.getCalculatedContextWindow(environmentSettings)))
                .map(ragDocs -> mergeContext(prompt, INSTRUCTIONS, ragDocs, debugArgs))
                .map(ragDoc -> llmClient.callWithCache(
                        ragDoc,
                        environmentSettings,
                        getName()));

        // Handle mapFailure in isolation to avoid intellij making a mess of the formatting
        // https://github.com/vavr-io/vavr/issues/2411
        return result.mapFailure(
                        API.Case(API.$(instanceOf(InternalFailure.class)), throwable -> throwable),
                        API.Case(API.$(instanceOf(FailedOllama.class)), throwable -> new InternalFailure(throwable.getMessage(), throwable)),
                        API.Case(API.$(instanceOf(EmptyString.class)),
                                throwable -> new InternalFailure("No diffs found for " + parsedArgs.getOwner() + "/" + parsedArgs.getRepo() + " between " + parsedArgs.getStartDate() + " and " + parsedArgs.getEndDate() + "\n" + debugArgs)),
                        API.Case(API.$(),
                                throwable -> new ExternalFailure("Failed to get diffs: " + throwable.getMessage() + "\n" + debugArgs)))
                .get();
    }

    private RagMultiDocumentContext<GitHubCommitAndDiff> mergeContext(final String prompt, final String instructions, final List<RagDocumentContext<GitHubCommitAndDiff>> context, final String debug) {
        return new RagMultiDocumentContext<>(
                prompt,
                instructions,
                context,
                debug);
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
        final String summary = parsedArgs.getSummarizeIndividualDiffs()
                ? getDiffSummary(commit.diff(), parsedArgs, environmentSettings)
                : commit.diff();

        return new RagDocumentContext<>(
                getName(),
                getContextLabel(),
                summary,
                sentenceSplitter.splitDocument(summary, 10)
                        .stream()
                        .map(sentenceVectorizer::vectorize)
                        .toList(),
                commit.commit().sha(),
                commit,
                "[" + GitHubUrlParser.urlToCommitHash(commit.commit().html_url()) + "](" + commit.commit().html_url() + ")");
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
                        "Provide a one paragraph summary of the changes in the Git Diff.",
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
    @ConfigProperty(name = "sb.ollama.gitdiffmodel")
    private Optional<String> configDiffModel;

    @Inject
    @ConfigProperty(name = "sb.ollama.diffcontextwindow")
    private Optional<String> configDiffContextWindow;

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
    @ConfigProperty(name = "sb.github.summarizeindividualdiffs")
    private Optional<String> configSummarizeIndividualDiffs;

    @Inject
    private ArgsAccessor argsAccessor;

    @Inject
    private Encryptor textEncryptor;

    @Inject
    private ValidateString validateString;

    @Inject
    private ModelConfig modelConfig;

    public Optional<String> getConfigDiffModel() {
        return configDiffModel;
    }

    public Optional<String> getConfigDiffContextWindow() {
        return configDiffContextWindow;
    }

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

    public Optional<String> getConfigSummarizeIndividualDiffs() {
        return configSummarizeIndividualDiffs;
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

    public ModelConfig getModelConfig() {
        return modelConfig;
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

        public boolean getSummarizeIndividualDiffs() {
            final String stringValue = getArgsAccessor().getArgument(
                    getConfigSummarizeIndividualDiffs()::get,
                    arguments,
                    context,
                    "summarizeIndividualDiffs",
                    "github_summarize_individual_diffs",
                    "").value();

            return Boolean.parseBoolean(stringValue);
        }

        public int getDays() {
            final String stringValue = getArgsAccessor().getArgument(
                    getConfigGithubDays()::get,
                    arguments,
                    context,
                    "days",
                    "github_days",
                    DEFAULT_DURATION).value();

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
                    "maxDiffs",
                    "github_max_diffs",
                    "0").value();

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
                    "since",
                    "github_since",
                    ZonedDateTime.now(ZoneOffset.UTC).minusDays(getDays()).format(FORMATTER)).value();
        }

        public String getEndDate() {
            return getArgsAccessor().getArgument(
                    getConfigGithubUntil()::get,
                    arguments,
                    context,
                    "until",
                    "github_until",
                    ZonedDateTime.now(ZoneOffset.UTC).format(FORMATTER)).value();
        }

        public String getOwner() {
            return getArgsAccessor().getArgument(
                    getConfigGithubOwner()::get,
                    arguments,
                    context,
                    "owner",
                    "github_owner",
                    DEFAULT_OWNER).value();
        }

        public String getRepo() {
            return getArgsAccessor().getArgument(
                    getConfigGithubRepo()::get,
                    arguments,
                    context,
                    "repo",
                    "github_repo",
                    DEFAULT_REPO).value();
        }

        public String getSha() {
            return getArgsAccessor().getArgument(
                    getConfigGithubSha()::get,
                    arguments,
                    context,
                    "sha",
                    "github_sha",
                    "").value();
        }

        public String getBranch() {
            return getArgsAccessor().getArgument(
                    getConfigGithubBranch()::get,
                    arguments,
                    context,
                    "branch",
                    "github_branch",
                    DEFAULT_BRANCH).value();
        }

        public String getToken() {
            return Try.of(() -> getTextEncryptor().decrypt(context.get("github_access_token")))
                    .recover(e -> context.get("github_access_token"))
                    .mapTry(getValidateString()::throwIfEmpty)
                    .recoverWith(e -> Try.of(getConfigGithubAccessToken()::get))
                    .getOrElseThrow(ex -> new InternalFailure("Failed to get GitHub access token", ex));
        }

        public String getDiffCustomModel() {
            return getArgsAccessor().getArgument(
                    getConfigDiffModel()::get,
                    arguments,
                    context,
                    "diffModel",
                    "github_diff_custom_model",
                    getModelConfig().getCalculatedModel(context)).value();
        }

        @Nullable
        public Integer getDiffContextWindow() {
            final String stringValue = getArgsAccessor().getArgument(
                    getConfigDiffContextWindow()::get,
                    arguments,
                    context,
                    "diffContextWindow",
                    "github_diff_context_window",
                    Constants.DEFAULT_CONTENT_WINDOW + "").value();

            return Try.of(() -> stringValue)
                    .map(Integer::parseInt)
                    .recover(e -> Constants.DEFAULT_CONTENT_WINDOW)
                    .get();
        }
    }
}

