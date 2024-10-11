package secondbrain.tools;

import io.vavr.control.Try;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.client.ClientBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jasypt.util.text.BasicTextEncryptor;
import org.jspecify.annotations.NonNull;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.date.DateParser;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.infrastructure.github.GitHubClient;
import secondbrain.infrastructure.github.GitHubCommitResponse;
import secondbrain.infrastructure.ollama.OllamaClient;
import secondbrain.infrastructure.ollama.OllamaGenerateBody;
import secondbrain.infrastructure.ollama.OllamaResponse;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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
    @ConfigProperty(name = "sb.encryption.password", defaultValue = "12345678")
    String encryptionPassword;

    @Inject
    @ConfigProperty(name = "sb.github.accesstoken")
    Optional<String> githubAccessToken;

    @Inject
    private ArgsAccessor argsAccessor;

    @Inject
    private GitHubClient gitHubClient;

    @Inject
    private OllamaClient ollamaClient;

    @Inject
    private DateParser dateParser;

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
        final String startTime = ZonedDateTime.now().minusDays(DEFAULT_DURATION).format(FORMATTER);
        final String endTime = ZonedDateTime.now().format(FORMATTER);

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
            @NotNull final Map<String, String> context,
            @NotNull final String prompt,
            @NotNull final List<ToolArgs> arguments) {

        final String startDate = argsAccessor.getArgument(arguments, "since", ZonedDateTime.now().minusDays(DEFAULT_DURATION).format(FORMATTER));
        final String endDate = argsAccessor.getArgument(arguments, "until", ZonedDateTime.now().format(FORMATTER));
        final String owner = argsAccessor.getArgument(arguments, "owner", DEFAULT_OWNER);
        final String repo = argsAccessor.getArgument(arguments, "repo", DEFAULT_REPO);
        final String branch = argsAccessor.getArgument(arguments, "branch", DEFAULT_BRANCH);

        final BasicTextEncryptor textEncryptor = new BasicTextEncryptor();
        textEncryptor.setPassword(encryptionPassword);

        // Try to decrypt the value, otherwise assume it is a plain text value, and finally
        // fall back to the value defined in the local configuration.
        final String token = Try.of(() -> textEncryptor.decrypt(context.get("github_access_token")))
                .recover(e -> context.get("github_access_token"))
                .mapTry(Objects::requireNonNull)
                .recoverWith(e -> Try.of(() -> githubAccessToken.get()))
                .get();

        final String authHeader = "Bearer " + token;

        return Try.of(() -> getCommits(
                        owner,
                        repo,
                        branch,
                        dateParser.parseDate(startDate).format(FORMATTER),
                        dateParser.parseDate(endDate).format(FORMATTER),
                        authHeader))
                .map(commitsResponse -> convertCommitsToDiffs(commitsResponse, owner, repo, authHeader))
                .map(diffs -> String.join("\n", diffs))
                .map(diffs -> buildToolPrompt(diffs, prompt))
                .map(this::callOllama)
                .map(OllamaResponse::response)
                .recover(throwable -> "Failed to get diffs: " + throwable.getMessage())
                .get();
    }

    private List<String> convertCommitsToDiffs(
            @NotNull final List<GitHubCommitResponse> commitsResponse,
            @NotNull final String owner,
            @NotNull final String repo,
            @NotNull final String authorization) {
        return commitsResponse.stream().map(commit -> diffToContext(owner, repo, commit, authorization)).toList();
    }

    private List<GitHubCommitResponse> getCommits(
            @NotNull final String owner,
            @NotNull final String repo,
            @NotNull final String branch,
            @NotNull final String since,
            @NotNull final String until,
            @NotNull final String authorization) {

        return Try.withResources(ClientBuilder::newClient)
                .of(client -> gitHubClient.getCommits(client, owner, repo, branch, until, since, authorization))
                .get();
    }

    private String diffToContext(
            @NotNull final String owner,
            @NotNull final String repo,
            @NotNull final GitHubCommitResponse commit,
            @NotNull final String authorization) {
        return "Git Diff:\n"
                + getCommitDiff(owner, repo, commit.sha(), authorization);
    }

    private OllamaResponse callOllama(@NotNull final String llmPrompt) {
        return Try.withResources(ClientBuilder::newClient)
                .of(client -> ollamaClient.getTools(
                        client,
                        new OllamaGenerateBody(model, llmPrompt, false)))
                .get();
    }

    private String getCommitDiff(
            @NotNull final String owner,
            @NotNull final String repo,
            @NotNull final String sha,
            @NotNull final String authorization) {

        return Try.withResources(ClientBuilder::newClient)
                .of(client -> gitHubClient.getDiff(client, owner, repo, sha, authorization)).get();
    }

    @NotNull
    public String buildToolPrompt(@NotNull final String context, @NonNull final String prompt) {
        return """
                <|begin_of_text|>
                <|start_header_id|>system<|end_header_id|>
                You are an expert in reading Git diffs. You are given a question and a list of Git diffs.
                You must answer the question based on the diffs provided.
                Here are the diffs:
                """
                + context.substring(0, Math.min(context.length(), Constants.MAX_CONTEXT_LENGTH))
                + "<|eot_id|><|start_header_id|>user<|end_header_id|>"
                + prompt
                + "<|eot_id|><|start_header_id|>assistant<|end_header_id|>".stripLeading();
    }
}
