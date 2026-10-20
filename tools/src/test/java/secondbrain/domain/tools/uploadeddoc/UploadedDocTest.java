package secondbrain.domain.tools.uploadeddoc;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Produces;
import io.vavr.control.Try;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import secondbrain.domain.args.ArgsAccessorSimple;
import secondbrain.domain.constants.Constants;
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
import secondbrain.domain.sanitize.GetFirstMarkdownBlock;
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
import secondbrain.domain.validate.ValidateStringBlank;
import secondbrain.infrastructure.llm.LlmClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(UploadedDoc.class)
@AddBeanClasses(UploadDocConfig.class)
@AddBeanClasses(ArgsAccessorSimple.class)
@AddBeanClasses(ValidateStringBlank.class)
@AddBeanClasses(SimpleSentenceSplitter.class)
@AddBeanClasses(DocumentTrimmerExactKeywords.class)
@AddBeanClasses(StandardExceptionMapping.class)
@AddBeanClasses(NamedHooksContainer.class)
@AddBeanClasses(Keywords.class)
@AddBeanClasses(GetFirstMarkdownBlock.class)
@AddBeanClasses(JsonDeserializerJackson.class)
@AddBeanClasses(Loggers.class)
class UploadedDocTest {
    private static final String PROMPT = "What does the uploaded document say?";
    private static final String TOOL = "UploadedDoc";
    private static final String CONTENTS = "The rollout starts in March. Billing changes in April. Support hours stay the same.";

    private final RecordingLlmClient llmClient = new RecordingLlmClient();
    private final StubSentenceVectorizer sentenceVectorizer = new StubSentenceVectorizer();
    private final RecordingFileToText fileToText = new RecordingFileToText();

    @Inject
    private UploadedDoc tool;

    @Inject
    private UploadDocConfig config;

    @Produces
    @Preferred
    LlmClient produceLlmClient() {
        return llmClient;
    }

    @Produces
    SentenceVectorizer produceSentenceVectorizer() {
        return sentenceVectorizer;
    }

    @Produces
    FileToText produceFileToText() {
        return fileToText;
    }

    @Produces
    @Preferred
    LocalStorage produceLocalStorage() {
        return new RecordingLocalStorage();
    }

    @Produces
    ToStringGenerator produceToStringGenerator() {
        return TestArgs.toStringGenerator("uploaded-doc", 47);
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
    }

    private static String encode(final String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, String> env(final String filename) {
        return Map.of("document", encode(CONTENTS), "filename", filename);
    }

    private static Map<String, String> env() {
        return env("notes.txt");
    }

    private static List<ToolArgs> args(final String... nameValues) {
        final List<ToolArgs> arguments = new ArrayList<>();
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
        assertEquals("File Contents", tool.getContextLabel());
        assertEquals("Queries an uploaded document", tool.getDescription());
        assertEquals(List.of(CommonArguments.KEYWORDS_ARG, CommonArguments.AUTO_GENERATE_KEYWORDS_ARG, CommonArguments.KEYWORD_WINDOW_ARG),
                tool.getArguments().stream().map(ToolArguments::name).toList());
    }

    @Test
    void getContextWritesTheDocumentToATempFileAndConvertsIt() {
        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args());

        assertEquals(List.of(CONTENTS), documents(context));
        final RagDocumentContext<Void> document = context.getFirst();
        assertEquals(TOOL, document.tool());
        assertEquals("File Contents", document.contextLabel());
        assertNull(document.id());
        assertNull(document.link());
        assertEquals(List.of(), document.keywordMatches());
        // The temp file keeps the uploaded file's extension and contains the decoded bytes
        assertEquals(1, fileToText.paths.size());
        assertTrue(fileToText.paths.getFirst().endsWith(".txt"), fileToText.paths.getFirst());
        assertEquals(List.of(CONTENTS), fileToText.contents);
        assertTrue(llmClient.requests.isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"report.pdf, .pdf", "archive.tar.gz, .gz", "README, ."})
    void getContextUsesTheFileExtensionForTheTempFile(final String filename, final String suffix) {
        tool.getContext(env(filename), List.of(PROMPT), args());

        assertTrue(fileToText.paths.getFirst().endsWith(suffix), fileToText.paths.getFirst());
    }

    @Test
    void getContextConvertsADocumentWithoutAFilename() {
        final List<RagDocumentContext<Void>> context = tool.getContext(Map.of("document", encode(CONTENTS)), List.of(PROMPT), args());

        // Without a filename the temp file has no extension, like a filename without one
        assertEquals(List.of(CONTENTS), documents(context));
        assertEquals(1, fileToText.paths.size());
        assertTrue(fileToText.paths.getFirst().endsWith("."), fileToText.paths.getFirst());
        assertEquals(List.of(CONTENTS), fileToText.contents);
    }

    @Test
    void getContextVectorizesTheDocumentForTheEntity() {
        tool.getContext(Map.of("document", encode(CONTENTS), "filename", "notes.txt", CommonArguments.ENTITY_NAME_CONTEXT_ARG, "Acme"),
                List.of(PROMPT), args());

        assertEquals(List.of("Acme"), sentenceVectorizer.hiddenText);
    }

    @Test
    void getContextReturnsNothingWithoutADocument() {
        assertEquals(List.of(), tool.getContext(Map.of("filename", "notes.txt"), List.of(PROMPT), args()));
        assertTrue(fileToText.paths.isEmpty());
    }

    @Test
    void getContextReturnsNothingForAnInvalidOrEmptyDocument() {
        assertEquals(List.of(), tool.getContext(Map.of("document", "%%% not base64 %%%", "filename", "notes.txt"), List.of(PROMPT), args()));
        assertEquals(List.of(), tool.getContext(Map.of("document", "", "filename", "notes.txt"), List.of(PROMPT), args()));
        assertTrue(fileToText.paths.isEmpty());
    }

    @Test
    void getContextTrimsTheDocumentAroundKeywords() {
        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT),
                args(CommonArguments.KEYWORDS_ARG, "Billing", CommonArguments.KEYWORD_WINDOW_ARG, "20"));

        final RagDocumentContext<Void> document = context.getFirst();
        assertNotEquals(CONTENTS, document.document());
        assertTrue(document.document().contains("Billing"), document.document());
        assertTrue(CONTENTS.contains(document.document()), document.document());
        assertFalse(document.document().contains("Support hours"), document.document());
        // Keyword matches are reported in lower case
        assertEquals(List.of("billing"), document.keywordMatches());
    }

    @Test
    void getContextReturnsNothingWhenNoKeywordsMatch() {
        assertEquals(List.of(), tool.getContext(env(), List.of(PROMPT), args(CommonArguments.KEYWORDS_ARG, "Kubernetes")));
        assertEquals(1, fileToText.paths.size());
    }

    @Test
    void getContextReturnsNothingWhenTheConvertedFileIsBlank() {
        fileToText.override = " ";

        assertEquals(List.of(), tool.getContext(env(), List.of(PROMPT), args()));
    }

    @Test
    void getContextReturnsNothingWhenVectorizingFails() {
        sentenceVectorizer.failAll();

        assertEquals(List.of(), tool.getContext(env(), List.of(PROMPT), args()));
    }

    @Test
    void getContextAppliesHooks() {
        final List<RagDocumentContext<Void>> context = tool.getContext(env(), List.of(PROMPT), args(
                CommonArguments.PREINITIALIZATION_HOOKS_ARG, "append",
                CommonArguments.PREPROCESSOR_HOOKS_ARG, "upper"));

        assertEquals(List.of("HOOK DOCUMENT", CONTENTS.toUpperCase(java.util.Locale.ROOT)), documents(context));
    }

    @Test
    void getContextHandlesAnEmptyPromptList() {
        assertEquals(List.of(CONTENTS), documents(tool.getContext(env(), List.of(), args())));
    }

    @Test
    void callAnswersFromTheDocumentAndAppliesPostInferenceHooks() {
        final RagMultiDocumentContext<Void> result = tool.call(env(), List.of(PROMPT), args(CommonArguments.POSTINFERENCE_HOOKS_ARG, "post"));

        assertEquals("answer [post]", result.getResponse());
        final RecordingLlmClient.Request request = llmClient.requests.getFirst();
        assertEquals(TOOL, request.tool());
        assertEquals(env(), request.environmentSettings());
        assertEquals(List.of(PROMPT), request.document().getPrompts());
        assertTrue(Objects.requireNonNull(request.document().instructions()).contains("The supplied document is the uploaded document."));
        assertEquals(List.of(CONTENTS), request.document().getIndividualContexts().stream().map(RagDocumentContext::document).toList());
    }

    @Test
    void callStillAsksTheLlmWithoutADocument() {
        final RagMultiDocumentContext<Void> result = tool.call(Map.of(), List.of(PROMPT), args());

        assertEquals("answer", result.getResponse());
        assertEquals(List.of(), llmClient.requests.getFirst().document().getIndividualContexts());
    }

    @Test
    void callMapsLlmFailuresToInternalFailure() {
        llmClient.respondWith(doc -> {
            throw new IllegalStateException("LLM down");
        });

        final InternalFailure ex = assertThrows(InternalFailure.class, () -> tool.call(env(), List.of(PROMPT), args(CommonArguments.POSTINFERENCE_HOOKS_ARG, "post")));

        assertInstanceOf(IllegalStateException.class, ex.getCause());
    }

    @Test
    void contextHashCodeCombinesArgumentsAndPrompts() {
        assertEquals(31 * 47 + List.of(PROMPT).hashCode(), tool.contextHashCode(env(), List.of(PROMPT), args()));
    }

    @Test
    void localArgumentsReadTheDocumentAndFilenameFromTheContext() {
        final UploadDocConfig.LocalArguments local = config.new LocalArguments(List.of(), List.of(PROMPT), env("plan.docx"));

        assertArrayEquals(CONTENTS.getBytes(StandardCharsets.UTF_8), local.getDocument());
        assertEquals("plan.docx", local.getFileName());
        assertArrayEquals(new byte[0], config.new LocalArguments(List.of(), List.of(PROMPT), Map.of()).getDocument());
    }

    @ParameterizedTest
    @CsvSource({"300, 300", "-1, -1", "wide, " + Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH, "' ', " + Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH})
    void localArgumentsParseTheKeywordWindow(final String value, final int expected) {
        assertEquals(expected, config.new LocalArguments(args(CommonArguments.KEYWORD_WINDOW_ARG, value), List.of(PROMPT), Map.of()).getKeywordWindow());
    }

    @Test
    void localArgumentsPreferTheContextOverArguments() {
        final Map<String, String> context = new HashMap<>();
        context.put(CommonArguments.KEYWORDS_ARG, "context-a, context-b");
        context.put(CommonArguments.CONTENT_RATING_QUESTION_ARG, "context question");
        context.put(CommonArguments.ENTITY_NAME_CONTEXT_ARG, "Context Entity");

        final UploadDocConfig.LocalArguments local = config.new LocalArguments(args(
                CommonArguments.KEYWORDS_ARG, "arg",
                CommonArguments.CONTENT_RATING_QUESTION_ARG, "arg question",
                CommonArguments.ENTITY_NAME_CONTEXT_ARG, "Arg Entity"), List.of(PROMPT), context);

        assertEquals(List.of("context-a", "context-b"), local.getKeywords());
        assertEquals("context question", local.getContextFilterQuestion());
        // The entity is only read from the context
        assertEquals("Context Entity", local.getEntity());
        assertEquals("", config.new LocalArguments(args(CommonArguments.ENTITY_NAME_CONTEXT_ARG, "Arg Entity"), List.of(PROMPT), Map.of()).getEntity());
    }

    @Test
    void localArgumentsDefaultToNoKeywordsOrHooks() {
        final UploadDocConfig.LocalArguments local = config.new LocalArguments(List.of(), List.of(PROMPT), Map.of());

        assertEquals(List.of(), local.getKeywords());
        assertFalse(local.getAutoGenerateKeywords());
        assertEquals("", local.getContextFilterQuestion());
        assertEquals("", local.getPreprocessingHooks());
        assertEquals("", local.getPreinitializationHooks());
        assertEquals("", local.getPostInferenceHooks());
        assertEquals(Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH, local.getKeywordWindow());
        assertEquals("uploaded-doc", local.toString());
    }

    @Test
    void localArgumentsGenerateKeywordsWhenRequested() {
        llmClient.respondWith(doc -> "[\"billing\"]");
        final String prompt = "Uploaded document question " + UUID.randomUUID();

        final UploadDocConfig.LocalArguments local = config.new LocalArguments(args(
                CommonArguments.AUTO_GENERATE_KEYWORDS_ARG, "true",
                CommonArguments.KEYWORDS_ARG, "april",
                CommonArguments.CONTENT_RATING_QUESTION_ARG, "Is it about money?"), List.of(prompt), Map.of());

        assertTrue(local.getAutoGenerateKeywords());
        assertEquals(List.of("april", "billing"), local.getKeywords());
        assertEquals(List.of(prompt + "\nIs it about money?"), llmClient.requests.getFirst().document().getPrompts());
    }

    /**
     * Reads the file written by the tool, recording the path and contents. The converted text can be overridden.
     */
    private static final class RecordingFileToText implements FileToText {
        final List<String> paths = new CopyOnWriteArrayList<>();
        final List<String> contents = new CopyOnWriteArrayList<>();
        @Nullable
        String override;

        @Override
        public String convert(final String path) {
            paths.add(path);
            final String text = Try.of(() -> Files.readString(Path.of(path))).get();
            contents.add(text);
            return override == null ? text : override;
        }

        @Override
        public String convertByteArray(final byte[] path) {
            throw new UnsupportedOperationException();
        }
    }
}
