package secondbrain.domain.tools.github;

import io.smallrye.common.annotation.Identifier;
import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import secondbrain.domain.args.ArgsAccessorSimple;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.context.SentenceVectorizer;
import secondbrain.domain.context.SimpleSentenceSplitter;
import secondbrain.domain.debug.DebugToolArgsKeyValue;
import secondbrain.domain.encryption.Encryptor;
import secondbrain.domain.exceptionhandling.StandardExceptionMapping;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.hooks.NamedHooksContainer;
import secondbrain.domain.hooks.PostInferenceHook;
import secondbrain.domain.hooks.PreProcessingHook;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.objects.ToStringGenerator;
import secondbrain.domain.processing.LLMRagDocSummarizer;
import secondbrain.domain.processing.MockRatingMetadata;
import secondbrain.domain.processing.RagDocSummarizerProducer;
import secondbrain.domain.processing.RatingMetadata;
import secondbrain.domain.processing.RatingToolRatingMetadata;
import secondbrain.domain.test.RecordingLlmClient;
import secondbrain.domain.test.StubSentenceVectorizer;
import secondbrain.domain.test.TestArgs;
import secondbrain.domain.test.TestConfigUtil;
import secondbrain.domain.test.TestHooks;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tools.CommonArguments;
import secondbrain.domain.validate.ValidateStringBlank;
import secondbrain.infrastructure.githubissues.GitHubIssuesClient;
import secondbrain.infrastructure.githubissues.api.GitHubIssue;
import secondbrain.infrastructure.llm.LlmClient;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import static java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME;
import static org.junit.jupiter.api.Assertions.*;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(GitHubIssues.class)
@AddBeanClasses(GitHubIssueConfig.class)
@AddBeanClasses(ArgsAccessorSimple.class)
@AddBeanClasses(ValidateStringBlank.class)
@AddBeanClasses(SimpleSentenceSplitter.class)
@AddBeanClasses(DebugToolArgsKeyValue.class)
@AddBeanClasses(StandardExceptionMapping.class)
@AddBeanClasses(NamedHooksContainer.class)
@AddBeanClasses(MockRatingMetadata.class)
@AddBeanClasses(LLMRagDocSummarizer.class)
@AddBeanClasses(RagDocSummarizerProducer.class)
@AddBeanClasses(Loggers.class)
// GitHub issues are built with null fields to match real responses
@SuppressWarnings("NullAway")
class GitHubIssuesTest {
    private static final String PROMPT = "Which bugs are open?";
    private static final String TOOL = "GitHubIssues";

    private final FakeGitHubIssuesClient issuesClient = new FakeGitHubIssuesClient();
    private final RecordingLlmClient llmClient = new RecordingLlmClient();

    @Inject
    private GitHubIssues tool;

    @Inject
    private MockRatingMetadata ratingMetadata;

    @Produces
    @Preferred
    GitHubIssuesClient produceGitHubIssuesClient() {
        return issuesClient;
    }

    @Produces
    @Preferred
    LlmClient produceLlmClient() {
        return llmClient;
    }

    @Produces
    @Preferred
    RatingMetadata produceRatingMetadata(final MockRatingMetadata mockRatingMetadata) {
        return mockRatingMetadata;
    }

    @Produces
    SentenceVectorizer produceSentenceVectorizer() {
        return new StubSentenceVectorizer();
    }

    @Produces
    @Identifier("AES")
    Encryptor produceEncryptor() {
        return TestArgs.encryptor();
    }

    @Produces
    ToStringGenerator produceToStringGenerator() {
        return TestArgs.toStringGenerator("github-issues", 11);
    }

    @Produces
    PreProcessingHook produceAppendHook() {
        return TestHooks.appendDocument("append", "Hook label", "hook document");
    }

    @Produces
    PreProcessingHook produceUpperCaseHook() {
        return TestHooks.upperCase("upper");
    }

    @Produces
    PostInferenceHook producePostHook() {
        return TestHooks.appendResponse("post", " [post]");
    }

    @BeforeEach
    void setUp() {
        TestConfigUtil.registerConfig(Map.of());
        ratingMetadata.setMockRating(null);
        issuesClient.issues.add(issue(12L, "Login fails"));
        issuesClient.issues.add(issue(15L, "Crash on start"));
    }

    private static GitHubIssue issue(@Nullable final Long number, final String body) {
        return new GitHubIssue(number, 100L, "Title", body, null, "open", List.of(), "https://github.com/acme/api/issues/" + number);
    }

    private static Map<String, String> env() {
        return Map.of("github_access_token", "enc(gh-token)");
    }

    private static List<ToolArgs> args(final String... extraNameValues) {
        final List<ToolArgs> arguments = new ArrayList<>();
        arguments.add(new ToolArgs(GitHubIssues.GITHUB_ORGANIZATION_ARG, "acme", true));
        arguments.add(new ToolArgs(GitHubIssues.GITHUB_REPO_ARG, "api", true));
        for (int i = 0; i < extraNameValues.length; i += 2) {
            arguments.add(new ToolArgs(extraNameValues[i], extraNameValues[i + 1], true));
        }
        return arguments;
    }

    private static List<String> documents(final List<RagDocumentContext<Void>> context) {
        return context.stream().map(RagDocumentContext::document).toList();
    }

    @Test
    void describesItself() {
        assertEquals(TOOL, tool.getName());
        assertEquals("GitHub Issue", tool.getContextLabel());
        assertEquals("Returns the details of a GitHub issue.", tool.getDescription());
        assertEquals(List.of(), tool.getArguments());
    }

    @Test
    void getContextConvertsEachIssueToADocument() {
        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args());

        assertEquals(List.of("Login fails", "Crash on start"), documents(context));
        final RagDocumentContext<Void> first = context.getFirst();
        assertEquals("12", first.id());
        assertEquals("GitHub Issue", first.contextLabel());
        assertEquals(TOOL, first.tool());
        assertEquals("[acme/api 12](https://github.com/acme/api/issues/12)", first.link());
        // Without days or dates the issues are not limited by date
        assertEquals(List.of("gh-token|acme|api|null|null|[]|"), issuesClient.calls);
        assertTrue(llmClient.requests.isEmpty());
    }

    @Test
    void getContextPassesTheFiltersToTheClient() {
        tool.getContext(env(), List.of(PROMPT), args(
                GitHubIssues.GITHUB_ISSUE_LABELS_ARG, "bug,urgent",
                GitHubIssues.GITHUB_ISSUE_STATE_ARG, "closed",
                CommonArguments.START_DATE, "2024-01-01T00:00:00Z",
                CommonArguments.END_DATE, "2024-02-01T00:00:00Z"));

        assertEquals(List.of("gh-token|acme|api|2024-01-01T00:00:00Z|2024-02-01T00:00:00Z|[bug, urgent]|closed"), issuesClient.calls);
    }

    @Test
    void getContextCalculatesTheDateRangeFromDays() {
        final String since = OffsetDateTime.now(ZoneId.systemDefault()).truncatedTo(ChronoUnit.DAYS).minusDays(5).format(ISO_OFFSET_DATE_TIME);
        final String until = OffsetDateTime.now(ZoneId.systemDefault()).truncatedTo(ChronoUnit.DAYS).plusDays(1).format(ISO_OFFSET_DATE_TIME);

        tool.getContext(env(), List.of(PROMPT), args(CommonArguments.DAYS_ARG, "5"));

        assertEquals(List.of("gh-token|acme|api|" + since + "|" + until + "|[]|"), issuesClient.calls);
    }

    @Test
    void getContextUsesZeroForIssuesWithoutANumber() {
        issuesClient.issues.clear();
        issuesClient.issues.add(issue(null, "Unnumbered"));

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args());

        assertEquals("0", context.getFirst().id());
        assertEquals("[acme/api 0](https://github.com/acme/api/issues/null)", context.getFirst().link());
    }

    @Test
    void getContextAddsRatingMetadata() {
        ratingMetadata.setMockRating(7);

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args());

        // The rating is recorded but issues are not filtered by it
        assertEquals(2, context.size());
        assertEquals(7, context.getFirst().getMetadata().getIntValueByName(RatingToolRatingMetadata.FILTER_RATING_META, 0));
    }

    @Test
    void getContextSummarizesIssuesWhenRequested() {
        llmClient.respondWith(doc -> "summary of " + doc.getIndividualContexts().getFirst().document());

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args(CommonArguments.SUMMARIZE_DOCUMENT_ARG, "true"));

        assertEquals(List.of("summary of Login fails", "summary of Crash on start"), documents(context));
        assertEquals(List.of("Summarise the GitHub issue in three paragraphs"), llmClient.requests.getFirst().document().getPrompts());
    }

    @Test
    void getContextAppliesHooks() {
        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args(
                CommonArguments.PREINITIALIZATION_HOOKS_ARG, "append",
                CommonArguments.PREPROCESSOR_HOOKS_ARG, "upper"));

        assertEquals(List.of("HOOK DOCUMENT", "LOGIN FAILS", "CRASH ON START"), documents(context));
    }

    @Test
    void getContextReturnsNothingWhenTheClientFails() {
        issuesClient.fail = true;

        assertEquals(List.of(), tool.getContext(env(), List.of(PROMPT), args()));
        assertEquals(1, issuesClient.calls.size());
    }

    @Test
    void getContextReturnsNothingWithoutAnAccessToken() {
        assertEquals(List.of(), tool.getContext(Map.of(), List.of(PROMPT), args()));
        assertTrue(issuesClient.calls.isEmpty());
    }

    @Test
    void getContextHandlesAnEmptyPromptList() {
        assertEquals(List.of("Login fails", "Crash on start"), documents(tool.getContext(env(), List.of(), args())));
    }

    @Test
    void callAnswersFromTheIssuesAndAppliesPostInferenceHooks() {
        final RagMultiDocumentContext<Void> result = tool.call(env(), List.of(PROMPT), args(CommonArguments.POSTINFERENCE_HOOKS_ARG, "post"));

        assertEquals("answer [post]", result.getResponse());
        final RecordingLlmClient.Request request = llmClient.requests.getFirst();
        assertEquals(TOOL, request.tool());
        assertEquals(List.of(PROMPT), request.document().getPrompts());
        assertTrue(Objects.requireNonNull(request.document().instructions()).startsWith("You are an expert in reading GitHub issues diffs."));
        assertEquals(List.of("Login fails", "Crash on start"),
                request.document().getIndividualContexts().stream().map(RagDocumentContext::document).toList());
        assertEquals(String.join(System.lineSeparator(), "* githubOrganization: acme", "* githubRepo: api", "* postInferenceHooks: post"),
                request.document().debug());
    }

    @Test
    void callMapsLlmFailuresToInternalFailure() {
        llmClient.respondWith(doc -> {
            throw new IllegalStateException("LLM down");
        });

        final InternalFailure ex = assertThrows(InternalFailure.class, () -> tool.call(env(), List.of(PROMPT), args()));

        assertInstanceOf(IllegalStateException.class, ex.getCause());
    }

    @Test
    void contextHashCodeCombinesArgumentsAndPrompts() {
        assertEquals(31 * 11 + List.of(PROMPT).hashCode(), tool.contextHashCode(env(), List.of(PROMPT), args()));
    }

    /**
     * Records each call as "token|organisation|repo|since|to|labels|state" and returns the configured issues.
     */
    private static final class FakeGitHubIssuesClient implements GitHubIssuesClient {
        final List<GitHubIssue> issues = new ArrayList<>();
        final List<String> calls = new CopyOnWriteArrayList<>();
        boolean fail;

        @Override
        public List<GitHubIssue> getIssues(final String token, final String organisation, final String repo, @Nullable final String since,
                                           @Nullable final String to, final List<String> labels, final String state) {
            calls.add(String.join("|", token, organisation, repo, String.valueOf(since), String.valueOf(to), labels.toString(), state));
            if (fail) {
                throw new IllegalStateException("GitHub unavailable");
            }
            return issues;
        }
    }
}
