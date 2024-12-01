package secondbrain.domain.tools.github;

import io.vavr.control.Try;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientBuilder;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.context.*;
import secondbrain.domain.date.DateParser;
import secondbrain.domain.debug.DebugToolArgs;
import secondbrain.domain.encryption.Encryptor;
import secondbrain.domain.exceptions.EmptyString;
import secondbrain.domain.limit.ListLimiter;
import secondbrain.domain.prompt.PromptBuilderSelector;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.validate.ValidateString;
import secondbrain.infrastructure.github.GitHubClient;
import secondbrain.infrastructure.github.GitHubCommitResponse;
import secondbrain.infrastructure.ollama.OllamaClient;
import secondbrain.infrastructure.ollama.OllamaGenerateBody;
import secondbrain.infrastructure.ollama.OllamaGenerateBodyWithContext;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Dependent
public class GitHubDiffs implements Tool {
    private static final String DEFAULT_OWNER = "mcasperson";
    private static final String DEFAULT_REPO = "SecondBrain";
    private static final String DEFAULT_BRANCH = "main";
    private static final int DEFAULT_DURATION = 30;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final String INSTRUCTIONS = """
            You are an expert in reading Git diffs.
            You are given a question and a list of Git Diffs related to the question.
            You must assume the Git Diffs capture the changes to the Git repository mentioned in the question.
            You must assume the information required to answer the question is present in the Git Diffs.
            You must answer the question based on the Git Diffs provided.
            When the user asks a question indicating that they want to know the changes in the repository, you must generate the answer based on the Git Diffs.
            You will be penalized for suggesting manual steps to generate the answer.
            You will be penalized for responding that you don't have access to real-time data or repositories.
            If there are no Git Diffs, you must indicate that in the answer.
            """;
    private static final String DIFF_INSTRUCTIONS = """
            You are an expert in reading Git diffs.
            The summary is intended for a machine learning model.
            You must use plain and concise text in the output.
            You will be penalized for including a header or title in the output.
            You will be penalized for including any markdown or HTML in the output.
            You must include all classes, functions, and variables that have been added, removed, or modified in the diff.
            """;

    @Inject
    @ConfigProperty(name = "sb.ollama.model", defaultValue = "llama3.2")
    String model;

    @Inject
    @ConfigProperty(name = "sb.ollama.contentlength", defaultValue = "" + Constants.MAX_CONTEXT_LENGTH)
    String limit;

    @Inject
    @ConfigProperty(name = "sb.github.accesstoken")
    Optional<String> githubAccessToken;

    @Inject
    @ConfigProperty(name = "sb.annotation.minsimilarity", defaultValue = "0.5")
    String minSimilarity;

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
    private SimilarityCalculator similarityCalculator;

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
        final String startTime = ZonedDateTime.now(ZoneId.systemDefault()).minusDays(DEFAULT_DURATION).format(FORMATTER);
        final String endTime = ZonedDateTime.now(ZoneId.systemDefault()).format(FORMATTER);

        return List.of(
                new ToolArguments("owner", "The github owner to check", "mcasperson"),
                new ToolArguments("repo", "The repository to check", "SecondBrain"),
                new ToolArguments("branch", "The branch to check", "main"),
                new ToolArguments("since", "The optional date to start checking from", startTime),
                new ToolArguments("until", "The optional date to stop checking at", endTime),
                new ToolArguments("days", "The optional number of days worth of diffs to return", "0"),
                new ToolArguments("excludeRagVectors", "The optional flag to exclude RAG vectors", "false")
        );
    }

    @Override
    public String call(
            final Map<String, String> context,
            final String prompt,
            final List<ToolArgs> arguments) {

        final int days = Try.of(() -> Integer.parseInt(argsAccessor.getArgument(arguments, "days", "" + DEFAULT_DURATION)))
                .recover(throwable -> DEFAULT_DURATION)
                .map(i -> Math.max(0, i))
                .get();

        final boolean excludeRagVectors = Try.of(() -> Boolean.parseBoolean(argsAccessor.getArgument(arguments, "days", "false")))
                .recover(throwable -> false)
                .get();

        final String startDate = argsAccessor.getArgument(arguments, "since", ZonedDateTime.now(ZoneId.systemDefault()).minusDays(days).format(FORMATTER));
        final String endDate = argsAccessor.getArgument(arguments, "until", ZonedDateTime.now(ZoneId.systemDefault()).format(FORMATTER));
        final String owner = argsAccessor.getArgument(arguments, "owner", DEFAULT_OWNER);
        final String repo = argsAccessor.getArgument(arguments, "repo", DEFAULT_REPO);
        final String branch = argsAccessor.getArgument(arguments, "branch", DEFAULT_BRANCH);


        final float parsedMinSimilarity = Try.of(() -> Float.parseFloat(minSimilarity))
                .recover(throwable -> 0.5f)
                .get();


        // Try to decrypt the value, otherwise assume it is a plain text value, and finally
        // fall back to the value defined in the local configuration.
        final Try<String> token = Try.of(() -> textEncryptor.decrypt(context.get("github_access_token")))
                .recover(e -> context.get("github_access_token"))
                .mapTry(Objects::requireNonNull)
                .recoverWith(e -> Try.of(() -> githubAccessToken.get()));

        if (token.isFailure() || StringUtils.isBlank(token.get())) {
            return "Failed to get GitHub access token";
        }

        final String authHeader = "Bearer " + token.get();

        final String debugArgs = debugToolArgs.debugArgs(arguments, true);

        return Try.of(() -> getCommits(
                        owner,
                        repo,
                        branch,
                        dateParser.parseDate(startDate).format(FORMATTER),
                        dateParser.parseDate(endDate).format(FORMATTER),
                        authHeader))
                .map(commitsResponse -> convertCommitsToDiffs(commitsResponse, owner, repo, authHeader, excludeRagVectors))
                .map(list -> listLimiter.limitListContent(
                        list,
                        RagDocumentContext::document,
                        NumberUtils.toInt(limit, Constants.MAX_CONTEXT_LENGTH)))
                .map(this::mergeContext)
                // Make sure we had some content for the prompt
                .mapTry(mergedContext ->
                        validateString.throwIfEmpty(mergedContext, RagMultiDocumentContext::combinedDocument))
                .map(ragContext -> ragContext.updateDocument(
                        promptBuilderSelector.getPromptBuilder(model).buildFinalPrompt(
                                INSTRUCTIONS,
                                ragContext.combinedDocument(),
                                prompt)))
                .map(this::callOllama)
                .map(response -> response.annotateDocumentContext(
                        parsedMinSimilarity,
                        10,
                        sentenceSplitter,
                        similarityCalculator,
                        sentenceVectorizer)
                        + System.lineSeparator() + System.lineSeparator()
                        + "Diffs:" + System.lineSeparator()
                        + urlsToLinks(response.getIds())
                        + debugArgs)
                .recover(EmptyString.class, "No diffs found" + debugArgs)
                .recover(throwable -> "Failed to get diffs: " + throwable.getMessage() + debugArgs)
                .get();
    }

    private String urlsToLinks(final List<String> urls) {
        return urls.stream()
                .map(url -> "* [" + GitHubUrlParser.urlToCommitHash(url) + "](" + url + ")")
                .collect(Collectors.joining("\n"));
    }


    private RagMultiDocumentContext<Void> mergeContext(final List<RagDocumentContext<Void>> context) {
        return new RagMultiDocumentContext<Void>(
                context.stream()
                        .map(RagDocumentContext::document)
                        .collect(Collectors.joining("\n")),
                context);
    }


    private List<RagDocumentContext<Void>> convertCommitsToDiffs(
            final List<GitHubCommitResponse> commitsResponse,
            final String owner,
            final String repo,
            final String authorization,
            final boolean excludeRagVectors) {

        return commitsResponse
                .stream()
                .map(commit -> new RagDocumentContext<Void>(
                        promptBuilderSelector.getPromptBuilder(model).buildContextPrompt("Git Diff", getCommitDiff(owner, repo, commit.sha(), authorization)),
                        List.of(),
                        commit.html_url()))
                .map(context -> getCommitVectors(context, excludeRagVectors))
                .toList();
    }

    private RagDocumentContext<Void> getCommitVectors(final RagDocumentContext<Void> diff, boolean excludeRagVectors) {
        return new RagDocumentContext<>(
                diff.document(),
                excludeRagVectors ? List.of() : sentenceSplitter.splitDocument(getDiffSummary(diff.document()), 10)
                        .stream()
                        .map(sentenceVectorizer::vectorize)
                        .toList(),
                diff.id());
    }

    /**
     * Use the LLM to generate a plain text summary of the diff. This summary will be used to link the
     * final summary of all diffs to the changes in individual diffs.
     */
    private String getDiffSummary(final String diff) {
        return Try.withResources(ClientBuilder::newClient)
                .of(client -> ollamaClient.getTools(
                        client,
                        new OllamaGenerateBody(
                                model,
                                promptBuilderSelector.getPromptBuilder(model).buildFinalPrompt(
                                        DIFF_INSTRUCTIONS,
                                        diff,
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

    private RagMultiDocumentContext<Void> callOllama(final RagMultiDocumentContext<Void> llmPrompt) {
        return Try.withResources(ClientBuilder::newClient)
                .of(client -> ollamaClient.getTools(
                        client,
                        new OllamaGenerateBodyWithContext<Void>(model, llmPrompt, false)))
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
