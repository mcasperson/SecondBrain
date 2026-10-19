package secondbrain.domain.tools.githubslackpublicfile;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.jboss.weld.junit5.auto.ExcludeBeanClasses;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import secondbrain.domain.args.ArgsAccessorSimple;
import secondbrain.domain.concurrency.SharedVirtualThreadExecutor;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.exceptionhandling.LoggingExceptionHandler;
import secondbrain.domain.exceptionhandling.StandardExceptionMapping;
import secondbrain.domain.exceptions.ExternalFailure;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.objects.ToStringGenerator;
import secondbrain.domain.reader.FileReader;
import secondbrain.domain.test.RecordingLlmClient;
import secondbrain.domain.test.TestArgs;
import secondbrain.domain.test.TestConfigUtil;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tools.CommonArguments;
import secondbrain.domain.tools.github.GitHubDiffs;
import secondbrain.domain.tools.github.GitHubIssues;
import secondbrain.domain.tools.slack.SlackChannel;
import secondbrain.domain.validate.ValidateStringBlank;
import secondbrain.domain.yaml.YamlDeserializerJackson;
import secondbrain.infrastructure.llm.LlmClient;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(GitHubSlackPublicFile.class)
@AddBeanClasses(GitHubSlackPublicFileConfig.class)
@AddBeanClasses(ArgsAccessorSimple.class)
@AddBeanClasses(ValidateStringBlank.class)
@AddBeanClasses(YamlDeserializerJackson.class)
@AddBeanClasses(SharedVirtualThreadExecutor.class)
@AddBeanClasses(LoggingExceptionHandler.class)
@AddBeanClasses(StandardExceptionMapping.class)
@AddBeanClasses(Loggers.class)
// The downstream tools are replaced by mocks from producer methods
@ExcludeBeanClasses({GitHubIssues.class, GitHubDiffs.class, SlackChannel.class})
class GitHubSlackPublicFileTest {
    private static final String DIRECTORY_URL = "https://example.org/entities.yaml";
    private static final String PROMPT = "What changed?";
    private static final String DIRECTORY = """
            entities:
              - name: Acme
                slack: [acme-channel, " "]
                repos:
                  - organization: acme
                    repository: api
                  - organization: ""
                    repository: ignored
                issues:
                  - organization: acme
                    repository: issues
                    individualPrompt: Issue prompt
              - name: Globex
                prompt: Globex prompt
                slack: [globex-channel]
            """;

    private final Map<String, String> files = new HashMap<>(Map.of(DIRECTORY_URL, DIRECTORY));
    private final GitHubIssues gitHubIssues = mock(GitHubIssues.class);
    private final GitHubDiffs gitHubDiffs = mock(GitHubDiffs.class);
    private final SlackChannel slackChannel = mock(SlackChannel.class);
    private final RecordingLlmClient llmClient = new RecordingLlmClient();
    private final List<Call> calls = new CopyOnWriteArrayList<>();

    @Inject
    private GitHubSlackPublicFile tool;

    @Inject
    private GitHubSlackPublicFileConfig config;

    @Produces
    GitHubIssues produceGitHubIssues() {
        return gitHubIssues;
    }

    @Produces
    GitHubDiffs produceGitHubDiffs() {
        return gitHubDiffs;
    }

    @Produces
    SlackChannel produceSlackChannel() {
        return slackChannel;
    }

    @Produces
    @Preferred
    LlmClient produceLlmClient() {
        return llmClient;
    }

    @Produces
    FileReader produceFileReader() {
        return path -> Optional.ofNullable(files.get(path)).orElseThrow(() -> new IllegalArgumentException("No file " + path));
    }

    @Produces
    ToStringGenerator produceToStringGenerator() {
        return TestArgs.toStringGenerator("github-slack", 23);
    }

    @BeforeEach
    void setUp() {
        TestConfigUtil.registerConfig(Map.of());
        when(slackChannel.getContext(any(), any(), any())).thenAnswer(inv -> record("Slack", inv.getArgument(1), inv.getArgument(2)));
        when(gitHubIssues.getContext(any(), any(), any())).thenAnswer(inv -> record("Issues", inv.getArgument(1), inv.getArgument(2)));
        when(gitHubDiffs.getContext(any(), any(), any())).thenAnswer(inv -> record("Diffs", inv.getArgument(1), inv.getArgument(2)));
    }

    /**
     * Records the call and returns a single document named after the source and its first argument value.
     */
    private List<RagDocumentContext<Void>> record(final String source, final List<String> prompts, final List<ToolArgs> arguments) {
        final Map<String, String> args = arguments.stream().collect(Collectors.toMap(ToolArgs::argName, ToolArgs::argValue));
        calls.add(new Call(source, prompts, args));
        final String tool = switch (source) {
            case "Slack" -> "SlackChannel";
            case "Issues" -> "GitHubIssues";
            default -> "GitHubDiffs";
        };
        return List.of(new RagDocumentContext<>(tool, source, source + " document " + args.values().stream().sorted().toList(), List.of()));
    }

    private List<Call> callsTo(final String source) {
        return calls.stream().filter(c -> c.source().equals(source)).toList();
    }

    private static List<ToolArgs> args(final String... nameValues) {
        final List<ToolArgs> arguments = new ArrayList<>();
        arguments.add(new ToolArgs(GitHubSlackPublicFile.GITHUB_SLACK_PUBLICFILE_URL_ARG, DIRECTORY_URL, true));
        for (int i = 0; i < nameValues.length; i += 2) {
            arguments.add(new ToolArgs(nameValues[i], nameValues[i + 1], true));
        }
        return arguments;
    }

    @Test
    void describesItself() {
        assertEquals("GitHubSlackPublicFile", tool.getName());
        assertEquals("Unused", tool.getContextLabel());
        assertEquals("Queries GitHub Issues, Slack channels, and public files to find relevant information.", tool.getDescription());
        assertEquals(List.of(), tool.getArguments());
    }

    @Test
    void getContextReturnsNothingWhenTheDirectoryCannotBeRead() {
        files.clear();

        assertEquals(List.of(), tool.getContext(Map.of(), List.of(PROMPT), args()));
        assertTrue(calls.isEmpty());
    }

    @Test
    void getContextQueriesEverySourceOfEveryEntityAndSortsByTool() {
        final List<RagDocumentContext<Void>> context = tool.getContext(Map.of(), List.of(PROMPT), args());

        assertEquals(List.of("GitHubDiffs", "GitHubIssues", "SlackChannel", "SlackChannel"), context.stream().map(RagDocumentContext::tool).toList());
        // Blank channels and repos without an organization are skipped
        assertEquals(Set.of("acme-channel", "globex-channel"),
                callsTo("Slack").stream().map(c -> c.args().get(SlackChannel.SLACK_CHANEL_ARG)).collect(Collectors.toSet()));
        assertEquals(1, callsTo("Diffs").size());
        assertEquals(1, callsTo("Issues").size());
    }

    @Test
    void getContextPassesSharedSettingsToSlack() {
        tool.getContext(Map.of(), List.of(PROMPT), args(
                CommonArguments.ENTITY_NAME_CONTEXT_ARG, "acme",
                CommonArguments.DAYS_ARG, "7",
                GitHubSlackPublicFile.GITHUB_SLACK_PUBLICFILE_CONTEXT_FILTER_QUESTION_ARG, "Relevant?",
                GitHubSlackPublicFile.GITHUB_SLACK_PUBLICFILE_CONTEXT_FILTER_MINIMUM_RATING_ARG, "6",
                GitHubSlackPublicFile.GITHUB_SLACK_PUBLICFILE_CONTEXT_SUMMARY_PROMPT_ARG, "Summarise"));

        assertEquals(List.of(new Call("Slack", List.of(PROMPT), Map.of(
                CommonArguments.CONTENT_RATING_QUESTION_ARG, "Relevant?",
                CommonArguments.CONTEXT_FILTER_MINIMUM_RATING_ARG, "6",
                CommonArguments.SUMMARIZE_DOCUMENT_ARG, "true",
                CommonArguments.SUMMARIZE_DOCUMENT_PROMPT_ARG, "Summarise",
                SlackChannel.SLACK_CHANEL_ARG, "acme-channel",
                CommonArguments.DAYS_ARG, "7"))), callsTo("Slack"));
    }

    @Test
    void getContextPassesRepositorySettingsToGitHub() {
        tool.getContext(Map.of(), List.of(PROMPT), args(
                CommonArguments.ENTITY_NAME_CONTEXT_ARG, "Acme",
                CommonArguments.DAYS_ARG, "3",
                GitHubSlackPublicFile.GITHUB_SLACK_PUBLICFILE_CONTEXT_FILTER_QUESTION_ARG, "Relevant?"));

        // The issue has its own prompt, so it is summarized even though there is no shared summary prompt
        assertEquals(Map.of(
                CommonArguments.CONTENT_RATING_QUESTION_ARG, "Relevant?",
                CommonArguments.CONTEXT_FILTER_MINIMUM_RATING_ARG, "0",
                CommonArguments.SUMMARIZE_DOCUMENT_ARG, "true",
                CommonArguments.SUMMARIZE_DOCUMENT_PROMPT_ARG, "Issue prompt",
                GitHubIssues.GITHUB_ORGANIZATION_ARG, "acme",
                GitHubIssues.GITHUB_REPO_ARG, "issues",
                CommonArguments.DAYS_ARG, "3"), callsTo("Issues").getFirst().args());
        // The repo has no prompt and there is no shared summary prompt, so diffs are not summarized
        assertEquals(Map.of(
                CommonArguments.SUMMARIZE_DOCUMENT_ARG, "false",
                CommonArguments.SUMMARIZE_DOCUMENT_PROMPT_ARG, "",
                "owner", "acme",
                "repo", "api",
                "days", "3"), callsTo("Diffs").getFirst().args());
    }

    @Test
    void getContextUsesTheEntityPromptWhenDefined() {
        tool.getContext(Map.of(), List.of(PROMPT), args(CommonArguments.ENTITY_NAME_CONTEXT_ARG, "acme,GLOBEX"));

        final Map<String, List<String>> promptsByChannel = callsTo("Slack").stream()
                .collect(Collectors.toMap(c -> c.args().get(SlackChannel.SLACK_CHANEL_ARG), Call::prompts));
        assertEquals(Map.of("acme-channel", List.of(PROMPT), "globex-channel", List.of("Globex prompt")), promptsByChannel);
    }

    @Test
    void getContextOnlyQueriesTheRequestedEntities() {
        final List<RagDocumentContext<Void>> context = tool.getContext(Map.of(), List.of(PROMPT), args(CommonArguments.ENTITY_NAME_CONTEXT_ARG, "Globex"));

        assertEquals(List.of("SlackChannel"), context.stream().map(RagDocumentContext::tool).toList());
        assertTrue(callsTo("Diffs").isEmpty());
        assertTrue(callsTo("Issues").isEmpty());
    }

    @Test
    void getContextIgnoresFailingSources() {
        // doThrow avoids invoking the answers registered in setUp while re-stubbing
        doThrow(new InternalFailure("slack down")).when(slackChannel).getContext(any(), any(), any());
        doThrow(new ExternalFailure("github down")).when(gitHubIssues).getContext(any(), any(), any());

        // Only the diffs remain
        assertEquals(List.of("GitHubDiffs"), tool.getContext(Map.of(), List.of(PROMPT), args()).stream().map(RagDocumentContext::tool).toList());

        // With every source failing there is no context
        doThrow(new IllegalStateException("unexpected")).when(gitHubDiffs).getContext(any(), any(), any());
        assertEquals(List.of(), tool.getContext(Map.of(), List.of(PROMPT), args()));
    }

    @Test
    void getContextReadsTheUrlFromTheContext() {
        final List<RagDocumentContext<Void>> context = tool.getContext(Map.of("multislackzengoogle_url", DIRECTORY_URL), List.of(PROMPT), List.of());

        assertEquals(4, context.size());
    }

    @Test
    void callSendsTheCombinedContextToTheLlm() {
        final RagMultiDocumentContext<Void> result = tool.call(Map.of(), List.of(PROMPT), args(CommonArguments.ENTITY_NAME_CONTEXT_ARG, "Globex"));

        assertEquals("answer", result.getResponse());
        final RagMultiDocumentContext<?> request = llmClient.requests.getFirst().document();
        assertEquals(List.of(PROMPT), request.getPrompts());
        assertTrue(Objects.requireNonNull(request.instructions()).contains("Slack channels, GitHub issues, and public web pages"));
        assertEquals(List.of("SlackChannel"), request.getIndividualContexts().stream().map(RagDocumentContext::tool).toList());
    }

    @Test
    void callStillQueriesTheLlmWhenThereIsNoContext() {
        files.clear();

        tool.call(Map.of(), List.of(PROMPT), args());

        assertEquals(List.of(), llmClient.requests.getFirst().document().getIndividualContexts());
    }

    @Test
    void callMapsLlmFailuresToInternalFailure() {
        llmClient.respondWith(doc -> {
            throw new IllegalStateException("LLM down");
        });

        assertThrows(InternalFailure.class, () -> tool.call(Map.of(), List.of(PROMPT), args()));
    }

    @Test
    void contextHashCodeCombinesArgumentsAndPrompts() {
        assertEquals(31 * 23 + List.of(PROMPT).hashCode(), tool.contextHashCode(Map.of(), List.of(PROMPT), args()));
    }

    @Test
    void argumentsHaveDefaultsAndBounds() {
        final GitHubSlackPublicFileConfig.LocalArguments defaults = config.new LocalArguments(List.of(), List.of(PROMPT), Map.of());
        assertEquals("", defaults.getUrl());
        assertEquals(0, defaults.getDays());
        assertEquals("", defaults.getIndividualContextFilterQuestion());
        assertEquals(0, defaults.getIndividualContextFilterMinimumRating());
        assertEquals("", defaults.getIndividualContextSummaryPrompt());
        assertEquals("", defaults.getAdditionalSystemPrompt());
        assertFalse(defaults.getStripMarkdownCodeBlock());
        assertEquals("", defaults.getAnnotationPrefix());
        assertEquals("", defaults.getMetaReport());
        assertEquals(List.of(), defaults.getEntityName());

        assertEquals(0, local(CommonArguments.DAYS_ARG, "-2").getDays());
        assertEquals(0, local(CommonArguments.DAYS_ARG, "week").getDays());
        assertEquals(0, local(GitHubSlackPublicFile.GITHUB_SLACK_PUBLICFILE_CONTEXT_FILTER_MINIMUM_RATING_ARG, "high").getIndividualContextFilterMinimumRating());
        assertEquals("Be terse", local(GitHubSlackPublicFile.GITHUB_SLACK_PUBLICFILE_ADDITIONAL_SYSTEM_PROMPT, "Be terse").getAdditionalSystemPrompt());
        assertTrue(local(GitHubSlackPublicFile.GITHUB_SLACK_PUBLICFILE_STRIP_MARKDOWN_CODE_BLOCK, "true").getStripMarkdownCodeBlock());
        assertEquals("ann", local(GitHubSlackPublicFile.GITHUB_SLACK_PUBLICFILE_ANNOTATION_PREFIX_ARG, "ann").getAnnotationPrefix());
        assertEquals("report.json", local(GitHubSlackPublicFile.GITHUB_SLACK_PUBLICFILE_META_REPORT_ARG, "report.json").getMetaReport());
        assertEquals(List.of("acme", "globex"), local(CommonArguments.ENTITY_NAME_CONTEXT_ARG, "Acme, GLOBEX").getEntityName());
        assertEquals("23", String.valueOf(defaults.hashCode()));
        assertEquals("github-slack", defaults.toString());
    }

    @Test
    void argumentsCanComeFromTheContext() {
        final GitHubSlackPublicFileConfig.LocalArguments local = config.new LocalArguments(List.of(), List.of(PROMPT), Map.of(
                "githubslackpublicfile_days", "9",
                "githubslackpublicfile_context_filter_question", "Context question?",
                "githubslackpublicfile_entity_name", "Initech"));

        assertEquals(9, local.getDays());
        assertEquals("Context question?", local.getIndividualContextFilterQuestion());
        assertEquals(List.of("initech"), local.getEntityName());
    }

    private GitHubSlackPublicFileConfig.LocalArguments local(final String name, final String value) {
        return config.new LocalArguments(List.of(new ToolArgs(name, value, true)), List.of(PROMPT), Map.of());
    }

    private record Call(String source, List<String> prompts, Map<String, String> args) {
    }
}
