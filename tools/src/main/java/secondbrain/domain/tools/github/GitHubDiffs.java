package secondbrain.domain.tools.github;

import io.smallrye.common.annotation.Identifier;
import io.vavr.API;
import io.vavr.control.Try;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientBuilder;
import org.apache.commons.lang3.math.NumberUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.context.SentenceSplitter;
import secondbrain.domain.context.SentenceVectorizer;
import secondbrain.domain.date.DateParser;
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
import secondbrain.infrastructure.github.GitHubCommitResponse;
import secondbrain.infrastructure.ollama.OllamaClient;
import secondbrain.infrastructure.ollama.OllamaGenerateBody;

import java.time.ZoneId;
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
@Dependent
public class GitHubDiffs implements Tool<GitHubCommitResponse> {
    private static final String DEFAULT_OWNER = "mcasperson";
    private static final String DEFAULT_REPO = "SecondBrain";
    private static final String DEFAULT_BRANCH = "main";
    private static final int DEFAULT_DURATION = 30;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
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
    @ConfigProperty(name = "sb.ollama.model", defaultValue = "llama3.2")
    private String model;

    /**
     * Each individual diff can be summarized with its own model. Typically, this model will be
     * smaller than the one used to provide the overall summary of the git diffs. For example,
     * diffModel might be something like "qwen2.5-coder:3b", while model will be something like
     * "qwen2.5-coder:14b".
     */
    @Inject
    @ConfigProperty(name = "sb.ollama.gitdiffmodel")
    private Optional<String> diffModel;

    @Inject
    @ConfigProperty(name = "sb.ollama.contentlength", defaultValue = "" + Constants.MAX_CONTEXT_LENGTH)
    private String limit;

    @Inject
    @ConfigProperty(name = "sb.github.accesstoken")
    private Optional<String> githubAccessToken;

    @Inject
    private Encryptor textEncryptor;
    @Inject
    private DebugToolArgs debugToolArgs;
    @Inject
    private ArgsAccessor argsAccessor;
    @Inject
    private GitHubClient gitHubClient;
    @Inject
    private OllamaClient ollamaClient;
    @Inject
    private DateParser dateParser;
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
    @Inject
    @Identifier("sanitizeDate")
    private SanitizeArgument dateSanitizer;

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
        final String startTime = ZonedDateTime.now(ZoneId.systemDefault()).minusDays(DEFAULT_DURATION).format(FORMATTER);
        final String endTime = ZonedDateTime.now(ZoneId.systemDefault()).format(FORMATTER);

        return List.of(
                new ToolArguments("owner", "The github owner to check", "mcasperson"),
                new ToolArguments("repo", "The repository to check", "SecondBrain"),
                new ToolArguments("branch", "The branch to check", "main"),
                new ToolArguments("since", "The optional date to start checking from", startTime),
                new ToolArguments("until", "The optional date to stop checking at", endTime),
                new ToolArguments("days", "The optional number of days worth of diffs to return", "0"),
                new ToolArguments("maxDiffs", "The optional number of diffs to return", "0")
        );
    }

    @Override
    public List<RagDocumentContext<GitHubCommitResponse>> getContext(
            final Map<String, String> context,
            final String prompt,
            final List<ToolArgs> arguments) {

        final Arguments parsedArgs = Arguments.fromToolArgs(
                arguments,
                context,
                argsAccessor,
                dateSanitizer,
                textEncryptor,
                validateString,
                githubAccessToken,
                model,
                prompt);

        final String authHeader = "Bearer " + parsedArgs.token();

        return Try.of(() -> getCommits(
                        parsedArgs.owner(),
                        parsedArgs.repo(),
                        parsedArgs.branch(),
                        dateParser.parseDate(parsedArgs.startDate()).format(FORMATTER),
                        dateParser.parseDate(parsedArgs.endDate()).format(FORMATTER),
                        authHeader))
                // limit the number of changes
                .map(commitsResponse -> ListUtilsEx.safeSubList(
                        commitsResponse,
                        0,
                        parsedArgs.maxDiffs() > 0 ? parsedArgs.maxDiffs() : commitsResponse.size()))
                .map(commitsResponse -> convertCommitsToDiffSummaries(
                        commitsResponse,
                        parsedArgs.owner(),
                        parsedArgs.repo(),
                        authHeader,
                        parsedArgs.customModel()))
                .get();
    }

    @Override
    public RagMultiDocumentContext<GitHubCommitResponse> call(
            final Map<String, String> context,
            final String prompt,
            final List<ToolArgs> arguments) {

        final Arguments parsedArgs = Arguments.fromToolArgs(
                arguments,
                context,
                argsAccessor,
                dateSanitizer,
                textEncryptor,
                validateString,
                githubAccessToken,
                model,
                prompt);

        final String debugArgs = debugToolArgs.debugArgs(arguments);

        final Try<RagMultiDocumentContext<GitHubCommitResponse>> result = Try.of(() -> getContext(context, prompt, arguments))
                .map(list -> listLimiter.limitListContent(
                        list,
                        RagDocumentContext::document,
                        NumberUtils.toInt(limit, Constants.MAX_CONTEXT_LENGTH)))
                .map(ragDocs -> mergeContext(ragDocs, debugArgs))
                // Make sure we had some content for the prompt
                .mapTry(mergedContext ->
                        validateString.throwIfEmpty(mergedContext, RagMultiDocumentContext::combinedDocument))
                .map(ragDoc -> ragDoc.updateDocument(
                        promptBuilderSelector.getPromptBuilder(parsedArgs.customModel()).buildFinalPrompt(
                                INSTRUCTIONS,
                                promptBuilderSelector.getPromptBuilder(parsedArgs.customModel()).buildContextPrompt("Git Diffs", ragDoc.combinedDocument()),
                                prompt)))
                .map(ragDoc -> ollamaClient.callOllama(ragDoc, parsedArgs.customModel()));

        // Handle mapFailure in isolation to avoid intellij making a mess of the formatting
        // https://github.com/vavr-io/vavr/issues/2411
        return result.mapFailure(
                        API.Case(API.$(instanceOf(EmptyString.class)),
                                throwable -> new FailedTool("No diffs found for " + parsedArgs.owner() + "/" + parsedArgs.repo() + " between " + parsedArgs.startDate() + " and " + parsedArgs.endDate() + "\n" + debugArgs)),
                        API.Case(API.$(),
                                throwable -> new FailedTool("Failed to get diffs: " + throwable.getMessage() + "\n" + debugArgs)))
                .get();
    }

    public String getContextHeader() {
        return "Git Diff";
    }

    private RagMultiDocumentContext<GitHubCommitResponse> mergeContext(final List<RagDocumentContext<GitHubCommitResponse>> context, final String debug) {
        return new RagMultiDocumentContext<>(
                context.stream()
                        .map(RagDocumentContext::document)
                        .collect(Collectors.joining("\n")),
                context,
                debug);
    }

    private List<RagDocumentContext<GitHubCommitResponse>> convertCommitsToDiffSummaries(
            final List<GitHubCommitResponse> commitsResponse,
            final String owner,
            final String repo,
            final String authorization,
            final String customModel) {

        return commitsResponse
                .stream()
                .map(commit -> getCommitSummary(commit, owner, repo, authorization, customModel))
                .toList();
    }

    /**
     * I could not get an LLM to provide a useful summary of a collection of Git Diffs. They would focus on the last diff
     * or hallucinate a bunch of random release notes. Instead, each diff is summarised individually and then combined
     * into a single document to be summarised again.
     */
    private RagDocumentContext<GitHubCommitResponse> getCommitSummary(final GitHubCommitResponse commit,
                                                                      final String owner,
                                                                      final String repo,
                                                                      final String authorization,
                                                                      final String customModel) {
        final String summary = getDiffSummary(getCommitDiff(owner, repo, commit.sha(), authorization), customModel);
        return new RagDocumentContext<GitHubCommitResponse>(
                summary,
                sentenceSplitter.splitDocument(summary, 10)
                        .stream()
                        .map(sentenceVectorizer::vectorize)
                        .toList(),
                commit.sha(),
                commit,
                "[" + GitHubUrlParser.urlToCommitHash(commit.html_url()) + "](" + commit.html_url() + ")");
    }

    /**
     * Use the LLM to generate a plain text summary of the diff. This summary will be used to link the
     * final summary of all diffs to the changes in individual diffs.
     */
    private String getDiffSummary(final String diff, final String customModel) {
        return Try.withResources(ClientBuilder::newClient)
                .of(client -> ollamaClient.callOllama(
                        client,
                        new OllamaGenerateBody(
                                diffModel.orElse(customModel),
                                promptBuilderSelector.getPromptBuilder(customModel).buildFinalPrompt(
                                        DIFF_INSTRUCTIONS,
                                        promptBuilderSelector.getPromptBuilder(customModel).buildContextPrompt("Git Diff", diff),
                                        "Provide a one paragraph summary of the changes in the Git Diff."),
                                false)))
                .get()
                .response();
    }

    private List<GitHubCommitResponse> getCommits(
            final String owner,
            final String repo,
            final String branch,
            final String since,
            final String until,
            final String authorization) {

        return Try.withResources(ClientBuilder::newClient)
                .of(client -> gitHubClient.getCommits(client, owner, repo, branch, until, since, authorization))
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

    /**
     * A record that hold the arguments used by the tool. This centralizes the logic for extracting, validating, and sanitizing
     * the various inputs to the tool.
     */
    record Arguments(int days, int maxDiffs, String startDate, String endDate, String owner, String repo,
                     String branch, String token, String customModel) {
        public static Arguments fromToolArgs(List<ToolArgs> arguments, Map<String, String> context, ArgsAccessor argsAccessor, SanitizeArgument dateSanitizer, Encryptor textEncryptor, ValidateString validateString, Optional<String> githubAccessToken, String model, String prompt) {
            final int days = Try.of(() -> Integer.parseInt(argsAccessor.getArgument(arguments, "days", "" + DEFAULT_DURATION)))
                    .recover(throwable -> DEFAULT_DURATION)
                    .map(i -> Math.max(0, i))
                    .map(i -> i == 0 ? DEFAULT_DURATION : i)
                    .get();

            final int maxDiffs = Try.of(() -> Integer.parseInt(argsAccessor.getArgument(arguments, "maxDiffs", "0")))
                    .recover(throwable -> 0)
                    .map(i -> Math.max(0, i))
                    .get();

            final String startDate = argsAccessor.getArgument(arguments, List.of(dateSanitizer), prompt, "since", ZonedDateTime.now(ZoneOffset.UTC).minusDays(days).format(FORMATTER));
            final String endDate = argsAccessor.getArgument(arguments, List.of(dateSanitizer), prompt, "until", ZonedDateTime.now(ZoneOffset.UTC).format(FORMATTER));
            final String owner = argsAccessor.getArgument(arguments, "owner", DEFAULT_OWNER);
            final String repo = argsAccessor.getArgument(arguments, "repo", DEFAULT_REPO);
            final String branch = argsAccessor.getArgument(arguments, "branch", DEFAULT_BRANCH);

            // Try to decrypt the value, otherwise assume it is a plain text value, and finally
            // fall back to the value defined in the local configuration.
            final String token = Try.of(() -> textEncryptor.decrypt(context.get("github_access_token")))
                    .recover(e -> context.get("github_access_token"))
                    .mapTry(validateString::throwIfEmpty)
                    .recoverWith(e -> Try.of(() -> githubAccessToken.get()))
                    .getOrElseThrow(ex -> new FailedTool("Failed to get GitHub access token", ex));

            final String customModel = Try.of(() -> context.get("custom_model"))
                    .mapTry(validateString::throwIfEmpty)
                    .recover(e -> model)
                    .get();

            return new Arguments(days, maxDiffs, startDate, endDate, owner, repo, branch, token, customModel);
        }
    }
}

