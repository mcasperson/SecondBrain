package secondbrain.domain.tools.publicfile;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import secondbrain.domain.args.ArgsAccessorSimple;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.context.SentenceVectorizer;
import secondbrain.domain.context.SimpleSentenceSplitter;
import secondbrain.domain.converter.FileToText;
import secondbrain.domain.exceptionhandling.StandardExceptionMapping;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.hooks.NamedHooksContainer;
import secondbrain.domain.hooks.PostInferenceHook;
import secondbrain.domain.hooks.PreProcessingHook;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.json.JsonDeserializerJackson;
import secondbrain.domain.limit.DocumentTrimmerExactKeywords;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.objects.ToStringGenerator;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.processing.SentenceVectorizerDataToRagDoc;
import secondbrain.domain.reader.FileReader;
import secondbrain.domain.sanitize.GetFirstMarkdownBlock;
import secondbrain.domain.test.*;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.tools.CommonArguments;
import secondbrain.domain.tools.keyword.Keywords;
import secondbrain.domain.validate.ValidateStringBlank;
import secondbrain.infrastructure.llm.LlmClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(PublicFile.class)
@AddBeanClasses(PublicWebConfig.class)
@AddBeanClasses(ArgsAccessorSimple.class)
@AddBeanClasses(ValidateStringBlank.class)
@AddBeanClasses(SentenceVectorizerDataToRagDoc.class)
@AddBeanClasses(SimpleSentenceSplitter.class)
@AddBeanClasses(DocumentTrimmerExactKeywords.class)
@AddBeanClasses(NamedHooksContainer.class)
@AddBeanClasses(StandardExceptionMapping.class)
@AddBeanClasses(Keywords.class)
@AddBeanClasses(GetFirstMarkdownBlock.class)
@AddBeanClasses(JsonDeserializerJackson.class)
@AddBeanClasses(Loggers.class)
class PublicFileTest {
    private static final String PROMPT = "What does the document say?";
    private static final String TOOL = "PublicFile";
    private static final String LABEL = "File Contents";
    private static final String URL = "https://example.org/doc.txt";
    private static final String TEXT = "start filler text needle and more filler text here";

    private final RecordingLlmClient llmClient = new RecordingLlmClient();
    private final StubSentenceVectorizer sentenceVectorizer = new StubSentenceVectorizer();
    private final Map<String, String> files = new HashMap<>(Map.of(URL, TEXT));
    private final List<String> readPaths = new CopyOnWriteArrayList<>();
    private final List<String> convertedPaths = new CopyOnWriteArrayList<>();

    // Assigned by JUnit before each test
    @TempDir
    @SuppressWarnings("NullAway.Init")
    Path tempDir;

    @Inject
    private PublicFile tool;

    @Produces
    @Preferred
    LlmClient produceLlmClient() {
        return llmClient;
    }

    @Produces
    @Preferred
    LocalStorage produceLocalStorage() {
        return new RecordingLocalStorage();
    }

    @Produces
    SentenceVectorizer produceSentenceVectorizer() {
        return sentenceVectorizer;
    }

    @Produces
    ToStringGenerator produceToStringGenerator() {
        return TestArgs.toStringGenerator("public-file", 53);
    }

    /**
     * Serves the registered files instead of downloading them.
     */
    @Produces
    FileReader produceFileReader() {
        return path -> {
            readPaths.add(path);
            return Objects.requireNonNull(files.get(path), () -> "No file " + path);
        };
    }

    /**
     * Reads local files, marking the text so tests can tell it was converted rather than downloaded.
     */
    @Produces
    FileToText produceFileToText() {
        return new FileToText() {
            @Override
            public String convert(final String path) {
                convertedPaths.add(path);
                try {
                    return "converted " + Files.readString(Path.of(path));
                } catch (final IOException e) {
                    throw new UncheckedIOException(e);
                }
            }

            @Override
            public String convertByteArray(final byte[] path) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Produces
    PreProcessingHook produceInitHook() {
        return TestHooks.appendDocument("Init", "Init Label", "init hook text");
    }

    @Produces
    PreProcessingHook produceUpperHook() {
        return TestHooks.upperCase("Upper");
    }

    @Produces
    PreProcessingHook produceAppendHook() {
        return TestHooks.appendDocument("Append", "Appended", "appended text");
    }

    @Produces
    PostInferenceHook produceExclaimHook() {
        return TestHooks.appendResponse("Exclaim", "!");
    }

    @Produces
    PostInferenceHook produceQuestionHook() {
        return TestHooks.appendResponse("Question", "?");
    }

    @BeforeEach
    void setUp() {
        TestConfigUtil.registerConfig(Map.of());
    }

    private static List<ToolArgs> args(final String url, final String... nameValues) {
        final List<ToolArgs> arguments = new ArrayList<>();
        arguments.add(new ToolArgs(PublicFile.PUBLICWEB_URL_ARG, url, true));
        for (int i = 0; i < nameValues.length; i += 2) {
            arguments.add(new ToolArgs(nameValues[i], nameValues[i + 1], true));
        }
        return arguments;
    }

    private static List<String> documents(final List<RagDocumentContext<Void>> context) {
        return context.stream().map(RagDocumentContext::document).toList();
    }

    @Test
    void describesItself() {
        assertEquals(TOOL, tool.getName());
        assertEquals(LABEL, tool.getContextLabel());
        assertEquals("Downloads a document from a supplied URL and queries it", tool.getDescription());
        assertEquals(List.of(PublicFile.PUBLICWEB_URL_ARG, CommonArguments.KEYWORD_WINDOW_ARG, CommonArguments.AUTO_GENERATE_KEYWORDS_ARG, CommonArguments.KEYWORDS_ARG),
                tool.getArguments().stream().map(ToolArguments::name).toList());
    }

    @Test
    void getContextReadsARemoteFile() {
        final List<RagDocumentContext<Void>> context = tool.getContext(Map.of(), List.of(PROMPT), args(URL));

        assertEquals(1, context.size());
        final RagDocumentContext<Void> document = context.getFirst();
        assertEquals(TEXT, document.document());
        assertEquals(URL, document.id());
        assertEquals(TOOL, document.tool());
        assertEquals(LABEL, document.contextLabel());
        assertEquals("[File](" + URL + ")", document.link());
        assertEquals(List.of(URL), readPaths);
        assertEquals(List.of(), convertedPaths);
        assertTrue(llmClient.requests.isEmpty());
    }

    @Test
    void getContextConvertsALocalFile() throws IOException {
        final Path file = Files.writeString(tempDir.resolve("local.txt"), "local contents");

        final List<RagDocumentContext<Void>> context = tool.getContext(Map.of(), List.of(PROMPT), args(file.toString()));

        assertEquals(List.of("converted local contents"), documents(context));
        assertEquals(file.toString(), context.getFirst().id());
        assertEquals(List.of(file.toString()), convertedPaths);
        assertEquals(List.of(), readPaths);
    }

    @Test
    void getContextReadsADirectoryPathWithTheFileReader() {
        // Only regular files are converted locally, anything else is passed to the file reader
        files.put(tempDir.toString(), "directory listing");

        assertEquals(List.of("directory listing"), documents(tool.getContext(Map.of(), List.of(PROMPT), args(tempDir.toString()))));
        assertEquals(List.of(tempDir.toString()), readPaths);
        assertEquals(List.of(), convertedPaths);
    }

    @Test
    void getContextReturnsNothingWhenTheFileCannotBeRead() {
        assertEquals(List.of(), tool.getContext(Map.of(), List.of(PROMPT), args("https://example.org/missing.txt")));
        assertEquals(List.of("https://example.org/missing.txt"), readPaths);
    }

    @Test
    void getContextReturnsNothingForAnEmptyFile() {
        files.put(URL, " ");

        assertEquals(List.of(), tool.getContext(Map.of(), List.of(PROMPT), args(URL)));
    }

    @Test
    void getContextReturnsNothingWithoutAUrl() {
        assertEquals(List.of(), tool.getContext(Map.of(), List.of(PROMPT), List.of()));
        assertEquals(List.of(), tool.getContext(Map.of(), List.of(PROMPT), args(" ")));
        assertEquals(List.of(), readPaths);
    }

    @Test
    void getContextReadsTheUrlFromTheContextBeforeTheArgument() {
        files.put("https://example.org/context.txt", "context file");

        assertEquals(List.of("context file"), documents(tool.getContext(Map.of(PublicFile.PUBLICWEB_URL_ARG, "https://example.org/context.txt"), List.of(PROMPT), args(URL))));
    }

    @Test
    void getContextTrimsTheDocumentAroundTheKeywords() {
        // A window of 20 keeps 5 characters before and 15 characters after the keyword
        final List<RagDocumentContext<Void>> context = tool.getContext(Map.of(), List.of(PROMPT),
                args(URL, CommonArguments.KEYWORDS_ARG, "needle", CommonArguments.KEYWORD_WINDOW_ARG, "20"));

        assertEquals(List.of("text needle and more"), documents(context));
        assertEquals(List.of("needle"), context.getFirst().keywordMatches());
    }

    @Test
    void getContextDropsDocumentsWithoutTheKeywords() {
        assertEquals(List.of(), tool.getContext(Map.of(), List.of(PROMPT), args(URL, CommonArguments.KEYWORDS_ARG, "absent")));
    }

    @Test
    void getContextPassesTheEntityToTheVectorizer() {
        tool.getContext(Map.of(CommonArguments.ENTITY_NAME_CONTEXT_ARG, "Acme"), List.of(PROMPT), args(URL));

        assertEquals(List.of("Acme"), sentenceVectorizer.hiddenText.stream().distinct().toList());
    }

    @Test
    void getContextPutsPreinitializationDocumentsFirst() {
        final List<RagDocumentContext<Void>> context = tool.getContext(Map.of(), List.of(PROMPT),
                args(URL, CommonArguments.PREINITIALIZATION_HOOKS_ARG, "Init"));

        assertEquals(List.of("init hook text", TEXT), documents(context));
        assertEquals(List.of("Init Label", LABEL), context.stream().map(RagDocumentContext::contextLabel).toList());
    }

    @Test
    void getContextKeepsPreinitializationDocumentsWhenTheFileCannotBeRead() {
        assertEquals(List.of("init hook text"), documents(tool.getContext(Map.of(), List.of(PROMPT),
                args("https://example.org/missing.txt", CommonArguments.PREINITIALIZATION_HOOKS_ARG, "Init"))));
    }

    @Test
    void getContextTrimsPreinitializationDocumentsToTheKeywords() {
        final List<RagDocumentContext<Void>> context = tool.getContext(Map.of(), List.of(PROMPT),
                args(URL, CommonArguments.PREINITIALIZATION_HOOKS_ARG, "Init", CommonArguments.KEYWORDS_ARG, "needle"));

        // The hook document does not contain the keyword, so it is removed
        assertEquals(List.of(TEXT), documents(context));
    }

    @Test
    void getContextAppliesPreprocessingHooksInOrder() {
        assertEquals(List.of(TEXT.toUpperCase(Locale.ROOT), "appended text"), documents(tool.getContext(Map.of(), List.of(PROMPT),
                args(URL, CommonArguments.PREPROCESSOR_HOOKS_ARG, "Upper,Append"))));
        assertEquals(List.of(TEXT.toUpperCase(Locale.ROOT), "APPENDED TEXT"), documents(tool.getContext(Map.of(), List.of(PROMPT),
                args(URL, CommonArguments.PREPROCESSOR_HOOKS_ARG, "Append,Upper"))));
    }

    @Test
    void getContextAppliesPreprocessingHooksToPreinitializationDocuments() {
        assertEquals(List.of("INIT HOOK TEXT", TEXT.toUpperCase(Locale.ROOT)), documents(tool.getContext(Map.of(), List.of(PROMPT),
                args(URL, CommonArguments.PREINITIALIZATION_HOOKS_ARG, "Init", CommonArguments.PREPROCESSOR_HOOKS_ARG, "Upper"))));
    }

    @Test
    void getContextHandlesAnEmptyPromptList() {
        assertEquals(List.of(TEXT), documents(tool.getContext(Map.of(), List.of(), args(URL))));
    }

    @Test
    void callAnswersFromTheDocument() {
        final Map<String, String> env = Map.of("key", "value");

        final RagMultiDocumentContext<Void> result = tool.call(env, List.of(PROMPT), args(URL));

        assertEquals("answer", result.getResponse());
        final RecordingLlmClient.Request request = llmClient.requests.getFirst();
        assertEquals(TOOL, request.tool());
        assertEquals(env, request.environmentSettings());
        assertEquals(List.of(PROMPT), request.document().getPrompts());
        assertTrue(Objects.requireNonNull(request.document().instructions()).contains("You are given a question and the contents of a document"));
        assertEquals(List.of(TEXT), request.document().getIndividualContexts().stream().map(RagDocumentContext::document).toList());
    }

    @Test
    void callAppliesPostInferenceHooksInOrder() {
        assertEquals("answer!?", tool.call(Map.of(), List.of(PROMPT), args(URL, CommonArguments.POSTINFERENCE_HOOKS_ARG, "Exclaim,Question")).getResponse());
        assertEquals("answer?!", tool.call(Map.of(), List.of(PROMPT), args(URL, CommonArguments.POSTINFERENCE_HOOKS_ARG, "Question,Exclaim")).getResponse());
    }

    @Test
    void callStillAsksTheLlmWhenTheFileCannotBeRead() {
        assertEquals("answer", tool.call(Map.of(), List.of(PROMPT), args("https://example.org/missing.txt")).getResponse());
        assertEquals(List.of(), llmClient.requests.getFirst().document().getIndividualContexts());
    }

    @Test
    void callRequiresAUrl() {
        final InternalFailure ex = assertThrows(InternalFailure.class, () -> tool.call(Map.of(), List.of(PROMPT), List.of()));

        assertEquals("You must provide a URL to download", ex.getMessage());
        assertTrue(llmClient.requests.isEmpty());
    }

    @Test
    void callMapsLlmFailuresToInternalFailure() {
        llmClient.respondWith(doc -> {
            throw new IllegalStateException("LLM down");
        });

        final InternalFailure ex = assertThrows(InternalFailure.class, () -> tool.call(Map.of(), List.of(PROMPT), args(URL, CommonArguments.POSTINFERENCE_HOOKS_ARG, "Exclaim")));

        assertInstanceOf(IllegalStateException.class, ex.getCause());
    }

    @Test
    void contextHashCodeCombinesArgumentsAndPrompts() {
        assertEquals(31 * 53 + List.of(PROMPT).hashCode(), tool.contextHashCode(Map.of(), List.of(PROMPT), args(URL)));
    }
}
