package secondbrain.domain.tools;

import io.vavr.control.Try;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientBuilder;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jasypt.util.text.BasicTextEncryptor;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.context.IndividualContext;
import secondbrain.domain.context.MergedContext;
import secondbrain.domain.date.DateParser;
import secondbrain.domain.debug.DebugToolArgs;
import secondbrain.domain.limit.ListLimiter;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.infrastructure.github.GitHubClient;
import secondbrain.infrastructure.github.GitHubCommitResponse;
import secondbrain.infrastructure.ollama.OllamaClient;
import secondbrain.infrastructure.ollama.OllamaGenerateBodyWithContext;
import secondbrain.infrastructure.ollama.OllamaResponseWithContext;

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

    @Inject
    @ConfigProperty(name = "sb.ollama.model", defaultValue = "llama3.2")
    String model;

    @Inject
    @ConfigProperty(name = "sb.ollama.contentlength", defaultValue = "" + Constants.MAX_CONTEXT_LENGTH)
    String limit;

    @Inject
    @ConfigProperty(name = "sb.encryption.password", defaultValue = "12345678")
    String encryptionPassword;

    @Inject
    @ConfigProperty(name = "sb.github.accesstoken")
    Optional<String> githubAccessToken;

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
                new ToolArguments("since", "The date to start checking from", startTime),
                new ToolArguments("until", "The date to stop checking at", endTime)
        );
    }

    @Override
    public String call(
            final Map<String, String> context,
            final String prompt,
            final List<ToolArgs> arguments) {

        final String startDate = argsAccessor.getArgument(arguments, "since", ZonedDateTime.now(ZoneId.systemDefault()).minusDays(DEFAULT_DURATION).format(FORMATTER));
        final String endDate = argsAccessor.getArgument(arguments, "until", ZonedDateTime.now(ZoneId.systemDefault()).format(FORMATTER));
        final String owner = argsAccessor.getArgument(arguments, "owner", DEFAULT_OWNER);
        final String repo = argsAccessor.getArgument(arguments, "repo", DEFAULT_REPO);
        final String branch = argsAccessor.getArgument(arguments, "branch", DEFAULT_BRANCH);

        final BasicTextEncryptor textEncryptor = new BasicTextEncryptor();
        textEncryptor.setPassword(encryptionPassword);

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

        return Try.of(() -> getCommits(
                        owner,
                        repo,
                        branch,
                        dateParser.parseDate(startDate).format(FORMATTER),
                        dateParser.parseDate(endDate).format(FORMATTER),
                        authHeader))
                .map(commitsResponse -> convertCommitsToDiffs(commitsResponse, owner, repo, authHeader))
                .map(list -> listLimiter.limitIndividualContextListContent(list, NumberUtils.toInt(limit, Constants.MAX_CONTEXT_LENGTH)))
                .map(this::mergeContext)
                .map(diffs -> buildToolPrompt(diffs, prompt))
                .map(this::callOllama)
                .map(response -> response.ollamaResponse().response()
                        + System.lineSeparator() + System.lineSeparator()
                        + "Diffs:" + System.lineSeparator()
                        + urlsToLinks(response.ids())
                        + debugToolArgs.debugArgs(arguments, true))
                .recover(throwable -> "Failed to get diffs: " + throwable.getMessage())
                .get();
    }

    private String urlsToLinks(final List<String> urls) {
        return urls.stream()
                .map(url -> "* [" + url + "](" + url + ")")
                .collect(Collectors.joining("\n"));
    }

    private MergedContext mergeContext(final List<IndividualContext<String>> context) {
        return new MergedContext(
                context.stream()
                        .map(IndividualContext::id)
                        .collect(Collectors.toList()),
                context.stream()
                        .map(IndividualContext::context)
                        .collect(Collectors.joining("\n")));
    }


    private List<IndividualContext<String>> convertCommitsToDiffs(
            final List<GitHubCommitResponse> commitsResponse,
            final String owner,
            final String repo,
            final String authorization) {
        return commitsResponse.stream().map(commit -> new IndividualContext<>(
                commit.html_url(),
                diffToContext(owner, repo, commit, authorization))).toList();
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


    private String diffToContext(
            final String owner,
            final String repo,
            final GitHubCommitResponse commit,
            final String authorization) {
        /*
        See https://github.com/meta-llama/llama-recipes/issues/450 for a discussion
        on the preferred format (or lack thereof) for RAG context.
        */
        return "<|start_header_id|>system<|end_header_id|>\n"
                + "Git Diff:\n"
                + getCommitDiff(owner, repo, commit.sha(), authorization)
                + "\n<|eot_id|>";
    }


    private OllamaResponseWithContext callOllama(final MergedContext llmPrompt) {
        return Try.withResources(ClientBuilder::newClient)
                .of(client -> ollamaClient.getTools(
                        client,
                        new OllamaGenerateBodyWithContext(model, llmPrompt, false)))
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


    public MergedContext buildToolPrompt(final MergedContext context, final String prompt) {
        return new MergedContext(context.ids(), """
                <|begin_of_text|>
                <|start_header_id|>system<|end_header_id|>
                You are an expert in reading Git diffs.
                You are given a question and a list of Git Diffs related to the question.
                You must assume the Git Diffs capture the changes to the Git repository mentioned in the question.
                You must assume the information required to answer the question is present in the Git Diffs.
                You must answer the question based on the Git Diffs provided.
                When the user asks a question indicating that they want to know the changes in the repository, you must generate the answer based on the Git Diffs.
                You will be penalized for suggesting manual steps to generate the answer.
                You will be penalized for responding that you don't have access to real-time data or repositories.
                If there are no Git Diffs, you must indicate that in the answer.
                <|eot_id|>
                """
                + context
                + "\n<|start_header_id|>user<|end_header_id|>"
                + prompt
                + "<|eot_id|>"
                + "\n<|start_header_id|>assistant<|end_header_id|>".stripLeading());
    }
}
