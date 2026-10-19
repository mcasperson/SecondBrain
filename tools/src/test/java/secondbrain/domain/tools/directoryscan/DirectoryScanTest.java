package secondbrain.domain.tools.directoryscan;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.apache.commons.codec.digest.DigestUtils;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.jboss.weld.junit5.auto.ExcludeBeanClasses;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import secondbrain.domain.args.ArgsAccessorSimple;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.context.SentenceVectorizer;
import secondbrain.domain.context.SimpleSentenceSplitter;
import secondbrain.domain.converter.FileToTextContext;
import secondbrain.domain.debug.DebugToolArgsKeyValue;
import secondbrain.domain.exceptionhandling.StandardExceptionMapping;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.files.AntPathMatcher;
import secondbrain.domain.hooks.NamedHooksContainer;
import secondbrain.domain.hooks.PostInferenceHook;
import secondbrain.domain.hooks.PreProcessingHook;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.limit.DocumentTrimmerExactKeywords;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.objects.ToStringGenerator;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.test.RecordingLlmClient;
import secondbrain.domain.test.RecordingLocalStorage;
import secondbrain.domain.test.StubSentenceVectorizer;
import secondbrain.domain.test.TestArgs;
import secondbrain.domain.test.TestConfigUtil;
import secondbrain.domain.test.TestHooks;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.tools.CommonArguments;
import secondbrain.domain.tools.keyword.Keywords;
import secondbrain.domain.validate.ValidateListEmptyOrNull;
import secondbrain.domain.validate.ValidateStringBlank;
import secondbrain.infrastructure.llm.LlmClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(DirectoryScan.class)
@AddBeanClasses(DirectoryScanConfig.class)
@AddBeanClasses(ArgsAccessorSimple.class)
@AddBeanClasses(ValidateStringBlank.class)
@AddBeanClasses(ValidateListEmptyOrNull.class)
@AddBeanClasses(DocumentTrimmerExactKeywords.class)
@AddBeanClasses(SimpleSentenceSplitter.class)
@AddBeanClasses(StandardExceptionMapping.class)
@AddBeanClasses(NamedHooksContainer.class)
@AddBeanClasses(DebugToolArgsKeyValue.class)
@AddBeanClasses(FileToTextContext.class)
@AddBeanClasses(AntPathMatcher.class)
@AddBeanClasses(Loggers.class)
// The keywords tool is replaced by a mock from a producer method
@ExcludeBeanClasses(Keywords.class)
class DirectoryScanTest {
    private static final String PROMPT = "Which files mention Acme?";
    private static final String FILE_INSTRUCTIONS_START = "You are given a question and the contents of a file.";
    private static final String INSTRUCTIONS_START = "You are given a question and the answer to the question from many individual files.";

    private final RecordingLlmClient llmClient = new RecordingLlmClient()
            .respondWith(doc -> "summary of " + doc.getIndividualContexts().getFirst().document());
    private final RecordingLocalStorage localStorage = new RecordingLocalStorage();
    private final StubSentenceVectorizer sentenceVectorizer = new StubSentenceVectorizer();

    // Assigned by JUnit before each test
    @TempDir
    @SuppressWarnings("NullAway.Init")
    Path dir;

    @Inject
    private DirectoryScan tool;

    @Produces
    @Preferred
    LlmClient produceLlmClient() {
        return llmClient;
    }

    @Produces
    @Preferred
    LocalStorage produceLocalStorage() {
        return localStorage;
    }

    @Produces
    SentenceVectorizer produceSentenceVectorizer() {
        return sentenceVectorizer;
    }

    @Produces
    Keywords produceKeywords() {
        return mock(Keywords.class);
    }

    @Produces
    ToStringGenerator produceToStringGenerator() {
        return TestArgs.toStringGenerator("directory-scan", 3);
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
    void setUp() throws IOException {
        TestConfigUtil.registerConfig(Map.of());
        Files.writeString(dir.resolve("a.txt"), "Acme renewed their contract.");
        Files.writeString(dir.resolve("b.md"), "Globex churned last quarter.");
        Files.createDirectories(dir.resolve("nested"));
        Files.writeString(dir.resolve("nested").resolve("c.txt"), "Acme opened a support ticket.");
    }

    private List<ToolArgs> args(final String... nameValues) {
        final List<ToolArgs> arguments = new ArrayList<>();
        arguments.add(new ToolArgs(DirectoryScan.DIRECTORYSCAN_DIRECTORY, dir.toString(), true));
        for (int i = 0; i < nameValues.length; i += 2) {
            arguments.add(new ToolArgs(nameValues[i], nameValues[i + 1], true));
        }
        return arguments;
    }

    private static List<RagDocumentContext<Void>> sorted(final List<RagDocumentContext<Void>> context) {
        return context.stream().sorted(Comparator.comparing(RagDocumentContext::getId)).toList();
    }

    private static List<String> fileNames(final List<RagDocumentContext<Void>> context) {
        return context.stream().map(c -> Path.of(c.getId()).getFileName().toString()).sorted().toList();
    }

    private String file(final String... path) {
        Path result = dir;
        for (final String part : path) {
            result = result.resolve(part);
        }
        return result.toString();
    }

    @Test
    void describesItself() {
        assertEquals("DirectoryScan", tool.getName());
        assertEquals("File Contents", tool.getContextLabel());
        assertTrue(tool.getDescription().startsWith("Scans the files in a directory and answers questions about them."));
        assertEquals(List.of("directory", "maxfiles"), tool.getArguments().stream().map(ToolArguments::name).toList());
    }

    @Test
    void getContextReturnsNothingWithoutADirectory() {
        assertEquals(List.of(), tool.getContext(Map.of(), List.of(PROMPT), List.of()));
        assertTrue(llmClient.requests.isEmpty());
    }

    @Test
    void getContextReturnsNothingForAMissingDirectory() {
        assertEquals(List.of(), tool.getContext(Map.of(), List.of(PROMPT),
                List.of(new ToolArgs(DirectoryScan.DIRECTORYSCAN_DIRECTORY, file("missing"), true))));
    }

    @Test
    void getContextSummarizesEveryFileRecursivelyByDefault() {
        final List<RagDocumentContext<Void>> context = sorted(tool.getContext(Map.of(), List.of(PROMPT), args()));

        assertEquals(List.of("a.txt", "b.md", "c.txt"), fileNames(context));
        final RagDocumentContext<Void> first = context.getFirst();
        assertEquals("summary of Acme renewed their contract.", first.document());
        assertEquals("DirectoryScan", first.tool());
        assertEquals("File Contents a.txt", first.contextLabel());
        assertEquals(file("a.txt"), first.id());
        assertEquals("[" + file("a.txt") + "](file://" + file("a.txt") + ")", first.link());

        final RecordingLlmClient.Request request = llmClient.requests.stream()
                .filter(r -> r.document().getIndividualContexts().getFirst().document().startsWith("Acme renewed"))
                .findFirst()
                .orElseThrow();
        assertEquals(List.of(PROMPT), request.document().getPrompts());
        assertTrue(Objects.requireNonNull(request.document().instructions()).startsWith(FILE_INSTRUCTIONS_START));
        assertTrue(localStorage.calls.contains(new RecordingLocalStorage.Call("DirectoryScan", "File",
                DigestUtils.sha256Hex(PROMPT + "Acme renewed their contract."), RecordingLocalStorage.NO_TTL)));
    }

    @Test
    void getContextUsesTheCustomSummaryPrompt() {
        tool.getContext(Map.of(), List.of(PROMPT), args(CommonArguments.SUMMARIZE_DOCUMENT_PROMPT_ARG, "List the companies"));

        assertTrue(llmClient.requests.stream().allMatch(r -> r.document().getPrompts().equals(List.of("List the companies"))));
    }

    @Test
    void getContextUsesCachedSummaries() {
        localStorage.prime("File", "cached summary");

        final List<RagDocumentContext<Void>> context = tool.getContext(Map.of(), List.of(PROMPT), args());

        assertEquals(List.of("cached summary", "cached summary", "cached summary"), context.stream().map(RagDocumentContext::document).toList());
        assertTrue(llmClient.requests.isEmpty());
    }

    @Test
    void getContextTreatsNullSummariesAsEmpty() {
        localStorage.primeNull("File");

        final List<RagDocumentContext<Void>> context = tool.getContext(Map.of(), List.of(PROMPT), args());

        assertEquals(List.of("", "", ""), context.stream().map(RagDocumentContext::document).toList());
    }

    @Test
    void getContextReturnsRawFilesWhenSummariesAreDisabled() {
        final List<RagDocumentContext<Void>> context = sorted(tool.getContext(Map.of(CommonArguments.ENTITY_NAME_CONTEXT_ARG, "Acme"), List.of(PROMPT),
                args(CommonArguments.SUMMARIZE_DOCUMENT_ARG, "false")));

        assertEquals(List.of("Acme renewed their contract.", "Globex churned last quarter.", "Acme opened a support ticket."),
                context.stream().map(RagDocumentContext::document).toList());
        assertEquals("File Contents c.txt", context.getLast().contextLabel());
        assertTrue(llmClient.requests.isEmpty());
        // The entity is passed to the vectorizer for raw files
        assertEquals(List.of("Acme", "Acme", "Acme"), sentenceVectorizer.hiddenText);
    }

    @Test
    void getContextExcludesNamedFiles() {
        final List<RagDocumentContext<Void>> context = tool.getContext(Map.of(), List.of(PROMPT),
                args(DirectoryScan.DIRECTORYSCAN_EXCLUDE_FILES, "a.txt, c.txt"));

        assertEquals(List.of("b.md"), fileNames(context));
    }

    @Test
    void getContextOnlyIncludesFilesMatchingThePathSpec() {
        assertEquals(List.of("a.txt", "c.txt"), fileNames(tool.getContext(Map.of(), List.of(PROMPT), args(DirectoryScan.DIRECTORYSCAN_PATHSPEC, "*.txt"))));
        assertEquals(List.of("a.txt", "b.md"), fileNames(tool.getContext(Map.of(), List.of(PROMPT), args(DirectoryScan.DIRECTORYSCAN_PATHSPEC, "a.*,*.md"))));
    }

    @Test
    void getContextLimitsTheNumberOfFiles() {
        assertEquals(2, tool.getContext(Map.of(), List.of(PROMPT), args(DirectoryScan.DIRECTORYSCAN_MAX_FILES, "2")).size());
        assertEquals(3, tool.getContext(Map.of(), List.of(PROMPT), args(DirectoryScan.DIRECTORYSCAN_MAX_FILES, "-1")).size());
        assertEquals(3, tool.getContext(Map.of(), List.of(PROMPT), args(DirectoryScan.DIRECTORYSCAN_MAX_FILES, "all")).size());
    }

    @Test
    void getContextScansEveryListedDirectory() throws IOException {
        final Path other = Files.createDirectories(dir.resolve("other"));
        Files.writeString(other.resolve("d.txt"), "Initech signed up.");

        final List<RagDocumentContext<Void>> context = tool.getContext(Map.of(), List.of(PROMPT), List.of(
                new ToolArgs(DirectoryScan.DIRECTORYSCAN_DIRECTORY, file("nested") + ", " + other, true)));

        assertEquals(List.of("c.txt", "d.txt"), fileNames(context));
    }

    @Test
    void getContextSkipsEmptyFilesAndFilesWithoutKeywords() throws IOException {
        Files.writeString(dir.resolve("empty.txt"), "");

        assertEquals(List.of("a.txt", "b.md", "c.txt"), fileNames(tool.getContext(Map.of(), List.of(PROMPT), args())));
        assertEquals(List.of("a.txt", "b.md", "c.txt"), fileNames(tool.getContext(Map.of(), List.of(PROMPT), args(CommonArguments.SUMMARIZE_DOCUMENT_ARG, "false"))));
        assertEquals(List.of("a.txt", "c.txt"), fileNames(tool.getContext(Map.of(), List.of(PROMPT), args(CommonArguments.KEYWORDS_ARG, "Acme"))));
        assertEquals(List.of("b.md"), fileNames(tool.getContext(Map.of(), List.of(PROMPT),
                args(CommonArguments.KEYWORDS_ARG, "Globex", CommonArguments.SUMMARIZE_DOCUMENT_ARG, "false"))));
    }

    @Test
    void getContextAppliesPreinitializationAndPreprocessingHooks() {
        final List<RagDocumentContext<Void>> context = tool.getContext(Map.of(), List.of(PROMPT), args(
                DirectoryScan.DIRECTORYSCAN_PATHSPEC, "a.txt",
                CommonArguments.SUMMARIZE_DOCUMENT_ARG, "false",
                CommonArguments.PREINITIALIZATION_HOOKS_ARG, "append",
                CommonArguments.PREPROCESSOR_HOOKS_ARG, "upper"));

        assertEquals(List.of("HOOK DOCUMENT", "ACME RENEWED THEIR CONTRACT."), context.stream().map(RagDocumentContext::document).toList());
    }

    @Test
    void callAnswersFromTheFileSummariesAndAppliesPostInferenceHooks() {
        llmClient.respondWith(doc -> doc.getIndividualContexts().size() == 1 ? "summary" : "final answer");

        final List<ToolArgs> arguments = args(DirectoryScan.DIRECTORYSCAN_MAX_FILES, "2", CommonArguments.POSTINFERENCE_HOOKS_ARG, "post");
        final RagMultiDocumentContext<Void> result = tool.call(Map.of(), List.of(PROMPT), arguments);

        assertEquals("final answer [post]", result.getResponse());
        final RagMultiDocumentContext<?> request = llmClient.requests.getLast().document();
        assertTrue(Objects.requireNonNull(request.instructions()).startsWith(INSTRUCTIONS_START));
        assertEquals(2, request.getIndividualContexts().size());
        assertEquals("* directory: " + dir + System.lineSeparator() + "* maxfiles: 2" + System.lineSeparator() + "* postInferenceHooks: post", request.debug());
        assertEquals("DirectoryScan", llmClient.requests.getLast().tool());
    }

    @Test
    void callFailsWhenThereAreNoFiles() {
        final InternalFailure ex = assertThrows(InternalFailure.class, () -> tool.call(Map.of(), List.of(PROMPT), args(DirectoryScan.DIRECTORYSCAN_PATHSPEC, "*.pdf")));

        assertTrue(llmClient.requests.isEmpty(), String.valueOf(ex.getMessage()));
    }

    @Test
    void contextHashCodeCombinesArgumentsAndPrompts() {
        assertEquals(31 * 3 + List.of(PROMPT).hashCode(), tool.contextHashCode(Map.of(), List.of(PROMPT), args()));
    }
}
