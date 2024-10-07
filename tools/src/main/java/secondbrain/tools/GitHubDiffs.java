package secondbrain.tools;

import io.vavr.control.Try;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.NonNull;
import secondbrain.domain.resteasy.ProxyCaller;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.date.DateParser;
import secondbrain.infrastructure.github.GitHub;
import secondbrain.infrastructure.github.GitHubCommitResponse;
import secondbrain.infrastructure.ollama.Ollama;
import secondbrain.infrastructure.ollama.OllamaGenerateBody;
import secondbrain.infrastructure.ollama.OllamaResponse;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Dependent
public class GitHubDiffs implements Tool {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_DATE_TIME;

    @Inject
    private ProxyCaller proxyCaller;

    @Override
    public String getName() {
        return GitHubDiffs.class.getSimpleName();
    }

    @Override
    public String getDescription() {
        return """
                Provides a list of Git diffs and answers questions about them.
                Example prompts include:
                * Given the diffs from owner "mcasperson" and repo "SecondBrain" on branch "main" between 2021-01-01 and 2021-01-31, generate release notes with an introductory paragraph and then bullet points of significant changes.
                """;
    }

    @Override
    public List<ToolArguments> getArguments() {
        final String startTime = ZonedDateTime.now().minusDays(30).format(FORMATTER);
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

        final LocalDate startDate = arguments.stream()
                .filter(arg -> arg.argName().equals("since"))
                .findFirst().map(arg -> DateParser.parseDate(arg.argValue()))
                .orElse(LocalDate.now().minusDays(30));

        final LocalDate endDate = arguments.stream()
                .filter(arg -> arg.argName().equals("until"))
                .findFirst().map(arg -> DateParser.parseDate(arg.argValue()))
                .orElse(LocalDate.now());

        final String owner = arguments.stream()
                .filter(arg -> arg.argName().equals("owner"))
                .findFirst().map(ToolArgs::argValue)
                .orElse("mcasperson");

        final String repo = arguments.stream()
                .filter(arg -> arg.argName().equals("repo"))
                .findFirst().map(ToolArgs::argValue)
                .orElse("SecondBrain");

        final String branch = arguments.stream()
                .filter(arg -> arg.argName().equals("branch"))
                .findFirst().map(ToolArgs::argValue)
                .orElse("main");

        final String token = context.getOrDefault("GITHUB_TOKEN", "");

        return Try.of(() -> getCommits(
                        owner,
                        repo,
                        branch,
                        startDate.format(FORMATTER),
                        endDate.format(FORMATTER),
                        "Bearer " + token))
                .map(commitsResponse -> convertCommitsToDiffs(commitsResponse, owner, repo, token))
                .map(diffs -> String.join("\n", diffs))
                .map(diffs -> buildToolPrompt(diffs, prompt))
                .map(this::callOllama)
                .map(OllamaResponse::response)
                .getOrElse("");
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
        return proxyCaller.callProxy(
                "https://api.github.com",
                GitHub.class,
                proxy -> proxy.getCommits(owner, repo, branch, until, since, authorization));
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
        return proxyCaller.callProxy(
                "http://localhost:11434",
                Ollama.class,
                simple -> simple.getTools(new OllamaGenerateBody("llama3.2", llmPrompt, false)));
    }

    private String getCommitDiff(
            @NotNull final String owner,
            @NotNull final String repo,
            @NotNull final String sha,
            @NotNull final String authorization) {
        return proxyCaller.callProxy(
                "https://api.github.com",
                GitHub.class,
                proxy -> proxy.getDiff(owner, repo, sha, authorization));
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
                + context
                + "<|eot_id|><|start_header_id|>user<|end_header_id|>"
                + prompt
                + "<|eot_id|><|start_header_id|>assistant<|end_header_id|>".stripLeading();
    }
}
