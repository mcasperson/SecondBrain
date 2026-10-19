package secondbrain.domain.tools.github;

import io.smallrye.common.annotation.Identifier;
import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import secondbrain.domain.test.FixedClientConstructor;
import secondbrain.domain.test.RecordingLlmClient;
import secondbrain.domain.test.StubSentenceVectorizer;
import secondbrain.domain.test.TestArgs;
import secondbrain.domain.test.TestConfigUtil;
import secondbrain.domain.test.TestHooks;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.tools.CommonArguments;
import secondbrain.domain.validate.ValidateStringBlank;
import secondbrain.domain.web.ClientConstructor;
import secondbrain.infrastructure.github.GitHubClient;
import secondbrain.infrastructure.github.api.GitHubCommitAndDiff;
import secondbrain.infrastructure.github.api.GitHubCommitResponse;
import secondbrain.infrastructure.github.api.GitHubCommitResponseCommit;
import secondbrain.infrastructure.llm.LlmClient;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(GitHubDiffs.class)
@AddBeanClasses(GitHubDiffConfig.class)
@AddBeanClasses(ArgsAccessorSimple.class)
@AddBeanClasses(ValidateStringBlank.class)
@AddBeanClasses(SimpleSentenceSplitter.class)
@AddBeanClasses(StandardExceptionMapping.class)
@AddBeanClasses(NamedHooksContainer.class)
@AddBeanClasses(DebugToolArgsKeyValue.class)
@AddBeanClasses(Loggers.class)
class GitHubDiffsTest {
    private static final String PROMPT = "Write release notes";
    private static final String AUTH = "Bearer gh-token";
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final String INSTRUCTIONS_START = "You are an expert in reading Git diffs.";

    private final FakeGitHubClient gitHubClient = new FakeGitHubClient();
    private final Client client = mock(Client.class);
    private final RecordingLlmClient llmClient = new RecordingLlmClient();

    @Inject
    private GitHubDiffs tool;

    @Inject
    private GitHubDiffConfig config;

    @Produces
    @Preferred
    GitHubClient produceGitHubClient() {
        return gitHubClient;
    }

    @Produces
    ClientConstructor produceClientConstructor() {
        return new FixedClientConstructor(client);
    }

    @Produces
    @Preferred
    LlmClient produceLlmClient() {
        return llmClient;
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
        return TestArgs.toStringGenerator("github-diffs", 29);
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
        gitHubClient.commits.add(commit("aaa", "Add feature"));
        gitHubClient.commits.add(commit("bbb", "Fix bug"));
        gitHubClient.commits.add(commit("ccc", "Refactor"));
    }

    private static GitHubCommitResponse commit(final String sha, final String message) {
        return new GitHubCommitResponse(sha, "https://github.com/acme/api/commit/" + sha, new GitHubCommitResponseCommit(message));
    }

    private static Map<String, String> env() {
        return Map.of("github_access_token", "enc(gh-token)");
    }

    private static List<ToolArgs> args(final String... nameValues) {
        final List<ToolArgs> arguments = new ArrayList<>();
        for (int i = 0; i < nameValues.length; i += 2) {
            arguments.add(new ToolArgs(nameValues[i], nameValues[i + 1], true));
        }
        return arguments;
    }

    private GitHubDiffConfig.LocalArguments local(final String... nameValues) {
        return config.new LocalArguments(args(nameValues), List.of(PROMPT), Map.of());
    }

    private static List<String> documents(final List<RagDocumentContext<Void>> context) {
        return context.stream().map(RagDocumentContext::document).toList();
    }

    @Test
    void describesItself() {
        assertEquals("GitHubDiffs", tool.getName());
        assertEquals("Git Diff", tool.getContextLabel());
        assertTrue(tool.getDescription().startsWith("Provides a list of Git diffs and answers questions about them."));
        assertEquals(List.of("owner", "repo", "branch", "sha", "startDate", "endDate", "days", "maxDiffs", "summarizeDocument", "summarizeDocumentPrompt"),
                tool.getArguments().stream().map(ToolArguments::name).toList());
    }

    @Test
    void getContextLoadsEveryCommitInTheDefaultRange() {
        final String since = ZonedDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS).minusDays(30).format(FORMATTER);
        final String until = ZonedDateTime.now(ZoneOffset.UTC).plusDays(1).truncatedTo(ChronoUnit.DAYS).format(FORMATTER);

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args());

        assertEquals(List.of("range:mcasperson/SecondBrain@main:" + since + ".." + until + ":" + AUTH,
                "diff:aaa:" + AUTH, "diff:bbb:" + AUTH, "diff:ccc:" + AUTH), gitHubClient.calls);
        assertEquals(List.of("Commit message:\nAdd feature\nDiff:\ndiff of aaa", "Commit message:\nFix bug\nDiff:\ndiff of bbb",
                "Commit message:\nRefactor\nDiff:\ndiff of ccc"), documents(context));
        final RagDocumentContext<Void> first = context.getFirst();
        assertEquals("aaa", first.id());
        assertEquals("Git Diff", first.contextLabel());
        assertEquals("GitHubDiffs", first.tool());
        assertEquals("[mcasperson/SecondBrain aaa](https://github.com/acme/api/commit/aaa)", first.link());
        assertTrue(llmClient.requests.isEmpty());
    }

    @Test
    void getContextUsesTheRequestedRepositoryAndDates() {
        tool.getContext(env(), List.of(PROMPT), args(
                GitHubDiffs.GITHUB_DIFF_OWNER_ARG, "acme",
                GitHubDiffs.GITHUB_DIFF_REPO_ARG, "api",
                GitHubDiffs.GITHUB_DIFF_BRANCH_ARG, "release",
                CommonArguments.START_DATE, "2024-01-01T00:00:00",
                CommonArguments.END_DATE, "2024-02-01T00:00:00"));

        assertEquals("range:acme/api@release:2024-01-01T00:00:00..2024-02-01T00:00:00:" + AUTH, gitHubClient.calls.getFirst());
    }

    @Test
    void getContextLimitsTheNumberOfDiffs() {
        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args(GitHubDiffs.GITHUB_DIFF_MAX_DIFFS_ARG, "2"));

        assertEquals(List.of("aaa", "bbb"), context.stream().map(RagDocumentContext::id).toList());
    }

    @Test
    void getContextLoadsSpecificCommitsWhenShasAreProvided() {
        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args(GitHubDiffs.GITHUB_DIFF_SHA_ARG, "bbb,ccc"));

        assertEquals(List.of("commits:mcasperson/SecondBrain:[bbb, ccc]:" + AUTH), gitHubClient.calls);
        assertEquals(List.of("bbb", "ccc"), context.stream().map(RagDocumentContext::id).toList());
        assertEquals("Commit message:\nFix bug\nDiff:\ndiff of bbb", context.getFirst().document());
    }

    @Test
    void getContextSummarizesEachDiffWhenRequested() {
        llmClient.respondWith(doc -> "summary of " + doc.getIndividualContexts().getFirst().document().lines().skip(1).findFirst().orElse(""));

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args(
                GitHubDiffs.GITHUB_DIFF_SHA_ARG, "aaa",
                CommonArguments.SUMMARIZE_DOCUMENT_ARG, "true"));

        assertEquals(List.of("summary of Add feature"), documents(context));
        final RagMultiDocumentContext<?> request = llmClient.requests.getFirst().document();
        assertEquals(List.of("Summarise the GitHub issue in three paragraphs"), request.getPrompts());
        assertEquals("You are a helpful agent", request.instructions());
    }

    @Test
    void getContextUsesAPlaceholderWhenSummarizingFails() {
        llmClient.respondWith(doc -> {
            throw new IllegalStateException("LLM down");
        });

        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args(
                GitHubDiffs.GITHUB_DIFF_MAX_DIFFS_ARG, "1",
                CommonArguments.SUMMARIZE_DOCUMENT_ARG, "true",
                CommonArguments.SUMMARIZE_DOCUMENT_PROMPT_ARG, "Summarise"));

        assertEquals(List.of("Failed to summarize diff"), documents(context));
        assertEquals(List.of("Summarise"), llmClient.requests.getFirst().document().getPrompts());
    }

    @Test
    void getContextAppliesHooksForCommitRanges() {
        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args(
                GitHubDiffs.GITHUB_DIFF_MAX_DIFFS_ARG, "1",
                CommonArguments.PREINITIALIZATION_HOOKS_ARG, "append",
                CommonArguments.PREPROCESSOR_HOOKS_ARG, "upper"));

        assertEquals(List.of("HOOK DOCUMENT", "COMMIT MESSAGE:\nADD FEATURE\nDIFF:\nDIFF OF AAA"), documents(context));
    }

    @Test
    void getContextAppliesHooksForSpecificCommits() {
        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args(
                GitHubDiffs.GITHUB_DIFF_SHA_ARG, "ccc",
                CommonArguments.PREINITIALIZATION_HOOKS_ARG, "append",
                CommonArguments.PREPROCESSOR_HOOKS_ARG, "upper"));

        assertEquals(List.of("HOOK DOCUMENT", "COMMIT MESSAGE:\nREFACTOR\nDIFF:\nDIFF OF CCC"), documents(context));
    }

    @Test
    void getContextReturnsNothingWithoutAnAccessToken() {
        assertEquals(List.of(), tool.getContext(Map.of(), List.of(PROMPT), args()));
        assertTrue(gitHubClient.calls.isEmpty());
    }

    @Test
    void getContextAcceptsPlainTextTokens() {
        tool.getContext(Map.of("github_access_token", "plain"), List.of(PROMPT), args(GitHubDiffs.GITHUB_DIFF_SHA_ARG, "aaa"));

        assertEquals("commits:mcasperson/SecondBrain:[aaa]:Bearer plain", gitHubClient.calls.getFirst());
    }

    @Test
    void getContextUsesTheConfiguredTokenWhenNoneIsInTheContext() {
        TestConfigUtil.registerConfig(Map.of("sb.github.accesstoken", "config-token"));

        tool.getContext(Map.of(), List.of(PROMPT), args(GitHubDiffs.GITHUB_DIFF_SHA_ARG, "aaa"));

        assertEquals("commits:mcasperson/SecondBrain:[aaa]:Bearer config-token", gitHubClient.calls.getFirst());
    }

    @Test
    void getContextReturnsNothingWhenGitHubFails() {
        gitHubClient.fail = true;

        assertEquals(List.of(), tool.getContext(env(), List.of(PROMPT), args()));
        assertEquals(List.of(), tool.getContext(env(), List.of(PROMPT), args(GitHubDiffs.GITHUB_DIFF_SHA_ARG, "aaa")));
    }

    @Test
    void callAnswersFromTheDiffsAndAppliesPostInferenceHooks() {
        final List<ToolArgs> arguments = args(GitHubDiffs.GITHUB_DIFF_SHA_ARG, "aaa", CommonArguments.POSTINFERENCE_HOOKS_ARG, "post");

        final RagMultiDocumentContext<Void> result = tool.call(env(), List.of(PROMPT), arguments);

        assertEquals("answer [post]", result.getResponse());
        final RagMultiDocumentContext<?> request = llmClient.requests.getLast().document();
        assertTrue(Objects.requireNonNull(request.instructions()).startsWith(INSTRUCTIONS_START));
        assertEquals("* sha: aaa" + System.lineSeparator() + "* postInferenceHooks: post", request.debug());
        assertEquals(List.of("aaa"), request.getIndividualContexts().stream().map(RagDocumentContext::id).toList());
    }

    @Test
    void callMapsLlmFailuresToInternalFailure() {
        llmClient.respondWith(doc -> {
            throw new IllegalStateException("LLM down");
        });

        assertThrows(InternalFailure.class, () -> tool.call(env(), List.of(PROMPT), args(GitHubDiffs.GITHUB_DIFF_SHA_ARG, "aaa")));
    }

    @Test
    void contextHashCodeCombinesArgumentsAndPrompts() {
        assertEquals(31 * 29 + List.of(PROMPT).hashCode(), tool.contextHashCode(env(), List.of(PROMPT), args()));
    }

    @ParameterizedTest
    @CsvSource({"7,7", "1,1", "0,30", "-5,30"})
    void daysDefaultToThirtyWhenNotPositive(final String value, final int expected) {
        assertEquals(expected, local(CommonArguments.DAYS_ARG, value).getDays());
    }

    @Test
    void daysRejectNonNumericValues() {
        assertEquals(30, local().getDays());
        assertThrows(NumberFormatException.class, () -> local(CommonArguments.DAYS_ARG, "week").getDays());
    }

    @ParameterizedTest
    @CsvSource({"5,5", "0,0", "-1,0", "many,0"})
    void maxDiffsAreNonNegative(final String value, final int expected) {
        assertEquals(expected, local(GitHubDiffs.GITHUB_DIFF_MAX_DIFFS_ARG, value).getMaxDiffs());
    }

    @Test
    void startDateIsBasedOnTheNumberOfDays() {
        final String expected = ZonedDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS).minusDays(3).format(FORMATTER);

        assertEquals(expected, local(CommonArguments.DAYS_ARG, "3").getStartDate());
    }

    @Test
    void argumentDefaults() {
        final GitHubDiffConfig.LocalArguments defaults = local();
        assertEquals("mcasperson", defaults.getOwner());
        assertEquals("SecondBrain", defaults.getRepo());
        assertEquals("main", defaults.getBranch());
        assertEquals("", defaults.getSha());
        assertFalse(defaults.getSummarizeDiff());
        assertEquals("", defaults.getPreprocessingHooks());
        assertEquals("", defaults.getPreinitializationHooks());
        assertEquals("", defaults.getPostInferenceHooks());
        assertEquals("github-diffs", defaults.toString());
    }

    /**
     * Records each call and serves commits from an in-memory list. Diffs are generated from the commit SHA.
     */
    private static final class FakeGitHubClient implements GitHubClient {
        final List<GitHubCommitResponse> commits = new ArrayList<>();
        final List<String> calls = new CopyOnWriteArrayList<>();
        boolean fail;

        private void checkFailure() {
            if (fail) {
                throw new IllegalStateException("GitHub unavailable");
            }
        }

        @Override
        public List<GitHubCommitResponse> getCommitsInRange(final Client client, final String owner, final String repo, final String sha,
                                                            final String until, final String since, final String authorization) {
            calls.add("range:" + owner + "/" + repo + "@" + sha + ":" + since + ".." + until + ":" + authorization);
            checkFailure();
            return commits;
        }

        @Override
        public GitHubCommitResponse getCommit(final Client client, final String owner, final String repo, final String sha, final String authorization) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<GitHubCommitAndDiff> getCommits(final Client client, final String owner, final String repo, final List<String> sha, final String authorization) {
            calls.add("commits:" + owner + "/" + repo + ":" + sha + ":" + authorization);
            checkFailure();
            return commits.stream()
                    .filter(c -> sha.contains(c.sha()))
                    .map(c -> new GitHubCommitAndDiff(c, "diff of " + c.sha()))
                    .toList();
        }

        @Override
        public String getDiff(final Client client, final String owner, final String repo, final String sha, final String authorization) {
            calls.add("diff:" + sha + ":" + authorization);
            return "diff of " + sha;
        }
    }
}
