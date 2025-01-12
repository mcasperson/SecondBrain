package secondbrain.domain.tools.github;

import io.smallrye.common.annotation.Identifier;
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
import secondbrain.domain.exceptions.FailedTool;
import secondbrain.domain.limit.ListLimiter;
import secondbrain.domain.list.ListUtilsEx;
import secondbrain.domain.prompt.PromptBuilderSelector;
import secondbrain.domain.sanitize.SanitizeArgument;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.validate.ValidateString;
import secondbrain.infrastructure.github.GitHubClient;
import secondbrain.infrastructure.github.GitHubCommitAndDiff;
import secondbrain.infrastructure.ollama.OllamaClient;
import secondbrain.infrastructure.ollama.OllamaGenerateBody;
import secondbrain.infrastructure.ollama.OllamaGenerateBodyOptions;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

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
    private Arguments parsedArgs;
    @Inject
    private DebugToolArgs debugToolArgs;
    @Inject
    private GitHubClient gitHubClient;
    @Inject
    private OllamaClient ollamaClient;
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
            final Map<String, String> context,
            final String prompt,
            final List<ToolArgs> arguments) {

        parsedArgs.setInputs(arguments, prompt, context);

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
                            .get());
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
                .map(this::convertCommitsToDiffSummaries)
                .get();
    }

    @Override
    public RagMultiDocumentContext<GitHubCommitAndDiff> call(
            final Map<String, String> context,
            final String prompt,
            final List<ToolArgs> arguments) {

        final String debugArgs = debugToolArgs.debugArgs(arguments);

        final Try<RagMultiDocumentContext<GitHubCommitAndDiff>> result = Try
                .of(() -> getContext(context, prompt, arguments))
                .map(list -> listLimiter.limitListContent(
                        list,
                        RagDocumentContext::document,
                        modelConfig.getCalculatedContextWindowChars()))
                .map(ragDocs -> mergeContext(ragDocs, debugArgs))
                // Make sure we had some content for the prompt
                .mapTry(mergedContext ->
                        validateString.throwIfEmpty(mergedContext, RagMultiDocumentContext::combinedDocument))
                .map(ragDoc -> ragDoc.updateDocument(
                        promptBuilderSelector.getPromptBuilder(modelConfig.getCalculatedModel(context)).buildFinalPrompt(
                                INSTRUCTIONS,
                                promptBuilderSelector.getPromptBuilder(modelConfig.getCalculatedModel(context)).buildContextPrompt("Git Diffs", ragDoc.combinedDocument()),
                                prompt)))
                .map(ragDoc -> ollamaClient.callOllama(
                        ragDoc,
                        modelConfig.getCalculatedModel(context),
                        modelConfig.getCalculatedContextWindow()));

        // Handle mapFailure in isolation to avoid intellij making a mess of the formatting
        // https://github.com/vavr-io/vavr/issues/2411
        return result.mapFailure(
                        API.Case(API.$(instanceOf(EmptyString.class)),
                                throwable -> new FailedTool("No diffs found for " + parsedArgs.getOwner() + "/" + parsedArgs.getRepo() + " between " + parsedArgs.getStartDate() + " and " + parsedArgs.getEndDate() + "\n" + debugArgs)),
                        API.Case(API.$(),
                                throwable -> new FailedTool("Failed to get diffs: " + throwable.getMessage() + "\n" + debugArgs)))
                .get();
    }

    private RagMultiDocumentContext<GitHubCommitAndDiff> mergeContext(final List<RagDocumentContext<GitHubCommitAndDiff>> context, final String debug) {
        return new RagMultiDocumentContext<>(
                context.stream()
                        .map(RagDocumentContext::document)
                        .collect(Collectors.joining("\n")),
                context,
                debug);
    }

    private List<RagDocumentContext<GitHubCommitAndDiff>> convertCommitsToDiffSummaries(
            final List<GitHubCommitAndDiff> commitsResponse) {

        return commitsResponse
                .stream()
                .map(this::getCommitSummary)
                .toList();
    }

    /**
     * I could not get an LLM to provide a useful summary of a collection of Git Diffs. They would focus on the last diff
     * or hallucinate a bunch of random release notes. Instead, each diff is summarised individually and then combined
     * into a single document to be summarised again.
     */
    private RagDocumentContext<GitHubCommitAndDiff> getCommitSummary(final GitHubCommitAndDiff commit) {
        /*
             We can optionally summarize each commit as a way of reducing the context size of
             when there are a lot of large diffs.
         */
        final String summary = parsedArgs.getSummarizeIndividualDiffs()
                ? getDiffSummary(commit.diff(), parsedArgs)
                : commit.diff();

        return new RagDocumentContext<>(
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
    private String getDiffSummary(final String diff, final Arguments parsedArgs) {
        return Try.withResources(ClientBuilder::newClient)
                .of(client -> ollamaClient.callOllama(
                        client,
                        new OllamaGenerateBody(
                                parsedArgs.getDiffCustomModel(),
                                promptBuilderSelector.getPromptBuilder(parsedArgs.getDiffCustomModel()).buildFinalPrompt(
                                        DIFF_INSTRUCTIONS,
                                        promptBuilderSelector.getPromptBuilder(parsedArgs.getDiffCustomModel()).buildContextPrompt("Git Diff", diff),
                                        "Provide a one paragraph summary of the changes in the Git Diff."),
                                false,
                                new OllamaGenerateBodyOptions(parsedArgs.getDiffContextWindow()))))
                .get()
                .response();
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
class Arguments {
    private static final String DEFAULT_OWNER = "mcasperson";
    private static final String DEFAULT_REPO = "SecondBrain";
    private static final String DEFAULT_BRANCH = "main";
    private static final int DEFAULT_DURATION = 30;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    @Inject
    @ConfigProperty(name = "sb.ollama.gitdiffmodel")
    private Optional<String> diffModel;

    @Inject
    @ConfigProperty(name = "sb.ollama.diffcontextwindow")
    private Optional<String> diffContextWindow;

    @Inject
    @ConfigProperty(name = "sb.github.accesstoken")
    private Optional<String> githubAccessToken;

    @Inject
    @ConfigProperty(name = "sb.github.owner")
    private Optional<String> githubOwner;

    @Inject
    @ConfigProperty(name = "sb.github.repo")
    private Optional<String> githubRepo;

    @Inject
    @ConfigProperty(name = "sb.github.sha")
    private Optional<String> githubSha;

    @Inject
    @ConfigProperty(name = "sb.github.summarizeindividualdiffs")
    private Optional<String> summarizeIndividualDiffs;

    @Inject
    private ArgsAccessor argsAccessor;

    @Inject
    private Encryptor textEncryptor;

    @Inject
    private ValidateString validateString;

    @Inject
    @Identifier("sanitizeDate")
    private SanitizeArgument dateSanitizer;

    @Inject
    private ModelConfig modelConfig;

    private List<ToolArgs> arguments;

    private String prompt;

    private Map<String, String> context;

    public void setInputs(final List<ToolArgs> arguments, final String prompt, final Map<String, String> context) {
        this.arguments = arguments;
        this.prompt = prompt;
        this.context = context;
    }

    public boolean getSummarizeIndividualDiffs() {
        return Try.of(summarizeIndividualDiffs::get)
                .mapTry(validateString::throwIfEmpty)
                .recover(e -> context.get("summarize_individual_diffs"))
                .mapTry(validateString::throwIfEmpty)
                .recover(e -> argsAccessor.getArgument(arguments, "summarizeIndividualDiffs", ""))
                .map(Boolean::parseBoolean)
                .get();
    }

    public int getDays() {
        return Try.of(() -> Integer.parseInt(argsAccessor.getArgument(arguments, "days", "" + DEFAULT_DURATION)))
                .recover(throwable -> DEFAULT_DURATION)
                .map(i -> Math.max(0, i))
                .map(i -> i == 0 ? DEFAULT_DURATION : i)
                .get();
    }

    public int getMaxDiffs() {
        return Try.of(() -> Integer.parseInt(argsAccessor.getArgument(arguments, "maxDiffs", "0")))
                .recover(throwable -> 0)
                .map(i -> Math.max(0, i))
                .get();
    }

    public String getStartDate() {
        return argsAccessor.getArgument(
                arguments,
                List.of(dateSanitizer),
                prompt,
                "since",
                ZonedDateTime.now(ZoneOffset.UTC).minusDays(getDays()).format(FORMATTER));
    }

    public String getEndDate() {
        return argsAccessor.getArgument(
                arguments,
                List.of(dateSanitizer),
                prompt,
                "until",
                ZonedDateTime.now(ZoneOffset.UTC).format(FORMATTER));
    }

    public String getOwner() {
        return Try.of(githubOwner::get)
                .mapTry(validateString::throwIfEmpty)
                .recover(e -> argsAccessor.getArgument(arguments, "owner", ""))
                .mapTry(validateString::throwIfEmpty)
                .recover(e -> context.get("github_owner"))
                .mapTry(validateString::throwIfEmpty)
                .recover(e -> DEFAULT_OWNER)
                .get();
    }

    public String getRepo() {
        return Try.of(githubRepo::get)
                .mapTry(validateString::throwIfEmpty)
                .recover(e -> argsAccessor.getArgument(arguments, "repo", ""))
                .mapTry(validateString::throwIfEmpty)
                .recover(e -> context.get("github_repo"))
                .mapTry(validateString::throwIfEmpty)
                .recover(e -> DEFAULT_REPO)
                .get();
    }

    public String getSha() {
        return Try.of(githubSha::get)
                .mapTry(validateString::throwIfEmpty)
                .recover(e -> argsAccessor.getArgument(arguments, "sha", ""))
                .mapTry(validateString::throwIfEmpty)
                .recover(e -> context.get("github_sha"))
                .getOrElse("");
    }

    public String getBranch() {
        return argsAccessor.getArgument(arguments, "branch", DEFAULT_BRANCH);
    }

    public String getToken() {
        return Try.of(() -> textEncryptor.decrypt(context.get("github_access_token")))
                .recover(e -> context.get("github_access_token"))
                .mapTry(validateString::throwIfEmpty)
                .recoverWith(e -> Try.of(() -> githubAccessToken.get()))
                .getOrElseThrow(ex -> new FailedTool("Failed to get GitHub access token", ex));
    }

    public String getDiffCustomModel() {
        return Try.of(() -> context.get("diff_custom_model"))
                .mapTry(validateString::throwIfEmpty)
                .recover(e -> diffModel.orElse(modelConfig.getCalculatedModel(context)))
                .get();
    }

    @Nullable
    public Integer getDiffContextWindow() {
        return Try.of(diffContextWindow::get)
                .map(Integer::parseInt)
                .recover(e -> Constants.DEFAULT_CONTENT_WINDOW)
                .get();
    }
}

