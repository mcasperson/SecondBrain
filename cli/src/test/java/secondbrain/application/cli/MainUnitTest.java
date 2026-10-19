package secondbrain.application.cli;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.converter.NoOpStringConverter;
import secondbrain.domain.converter.StringConverter;
import secondbrain.domain.converter.StringConverterSelector;
import secondbrain.domain.files.DefaultFileSanitizer;
import secondbrain.domain.files.DefaultPathBuilder;
import secondbrain.domain.handler.PromptHandler;
import secondbrain.domain.handler.PromptHandlerOutput;
import secondbrain.domain.handler.PromptHandlerResponse;
import secondbrain.domain.handler.PromptResponseSimple;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.persist.MockLocalStorage;
import secondbrain.domain.test.RecordingLlmClient;
import secondbrain.domain.test.TestConfigUtil;
import secondbrain.domain.toolbuilder.ToolSelector;
import secondbrain.domain.web.ClientConstructorDefault;
import secondbrain.domain.json.JsonDeserializerJackson;
import secondbrain.domain.validate.ValidateListEmptyOrNull;
import secondbrain.domain.toolbuilder.ToolBuilderLlama3;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.infrastructure.llm.LlmClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link Main} that replace the prompt handler, output and cache with test instances, so no Cosmos
 * emulator or LLM is needed. Config is read when a Main instance is created, so tests obtain Main from an Instance
 * after registering config.
 */
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(Main.class)
@AddBeanClasses(StringConverterSelector.class)
@AddBeanClasses(NoOpStringConverter.class)
@AddBeanClasses(DefaultPathBuilder.class)
@AddBeanClasses(DefaultFileSanitizer.class)
@AddBeanClasses(ToolSelector.class)
@AddBeanClasses(Loggers.class)
@AddBeanClasses(ClientConstructorDefault.class)
@AddBeanClasses(JsonDeserializerJackson.class)
@AddBeanClasses(ValidateListEmptyOrNull.class)
@AddBeanClasses(ToolBuilderLlama3.class)
class MainUnitTest {
    private static final List<String> ALL_OUTPUTS = List.of(
            "printOutput", "writeAnnotations", "writeLinks", "writeDebug", "writeOutput", "saveMetadata", "saveIntermediateResults");

    // Assigned by JUnit before each test
    @TempDir
    @SuppressWarnings("NullAway.Init")
    Path tempDir;

    private final RecordingPromptHandler promptHandler = new RecordingPromptHandler();
    private final RecordingOutput output = new RecordingOutput();
    private final FlushCountingLocalStorage localStorage = new FlushCountingLocalStorage();

    @Inject
    private Instance<Main> mainInstance;

    @Produces
    PromptHandler producePromptHandler() {
        return promptHandler;
    }

    @Produces
    PromptHandlerOutput producePromptHandlerOutput() {
        return output;
    }

    @Produces
    @Preferred
    LocalStorage produceLocalStorage() {
        return localStorage;
    }

    @Produces
    StringConverter produceUpperCaseConverter() {
        return new StringConverter() {
            @Override
            public String getFormat() {
                return "upper";
            }

            @Override
            public String convert(final String response) {
                return response.toUpperCase();
            }
        };
    }

    /**
     * ToolSelector needs an LLM client, but Main only uses it to list the available tools.
     */
    @Produces
    @Preferred
    LlmClient produceLlmClient() {
        return new RecordingLlmClient();
    }

    @Produces
    Tool<Void> produceTool() {
        return new StubTool();
    }

    @BeforeEach
    void setUp() {
        TestConfigUtil.registerConfig(Map.of());
    }

    private Main main() {
        return mainInstance.get();
    }

    @Test
    void entryWithPromptArgumentSendsPromptToHandlerAndWritesAllOutputs() {
        promptHandler.respondWith(prompts -> new PromptResponseSimple("Answer"));

        final PromptHandlerResponse response = main().entry(new String[]{"What is new?"});

        assertNotNull(response);
        assertEquals("Answer", response.getResponseText());
        assertEquals(List.of(new HandlerCall(Map.of(), List.of("What is new?"))), promptHandler.calls);
        assertEquals(ALL_OUTPUTS, output.methodNames());
        assertTrue(output.calls.stream().allMatch(call -> call.response() == response && call.context().isEmpty()));
        assertEquals(1, localStorage.flushes.get());
    }

    @Test
    void entryWithFormatArgumentConvertsResponseText() {
        promptHandler.respondWith(prompts -> new PromptResponseSimple("answer", "annotation", "link", "debug", List.of(), List.of()));

        final PromptHandlerResponse response = main().entry(new String[]{"prompt", "upper"});

        assertNotNull(response);
        assertEquals("ANSWER", response.getResponseText());
        assertEquals("ANNOTATION", response.getAnnotations());
        assertEquals("LINK", response.getLinks());
        assertEquals("DEBUG", response.getDebugInfo());
        // Outputs receive the converted response
        assertEquals("ANSWER", output.calls.get(0).response().getResponseText());
    }

    @Test
    void entryWithUnknownFormatLeavesResponseUnchanged() {
        promptHandler.respondWith(prompts -> new PromptResponseSimple("Mixed Case"));

        final PromptHandlerResponse response = main().entry(new String[]{"prompt", "unknown-format"});

        assertNotNull(response);
        assertEquals("Mixed Case", response.getResponseText());
    }

    @Test
    void entryWithHelpPrintsAvailableToolsAndSkipsPromptHandler() {
        final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        final PrintStream original = System.out;
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        final PromptHandlerResponse response;
        try {
            response = main().entry(new String[]{"--help"});
        } finally {
            System.setOut(original);
        }

        assertNull(response);
        final String printed = captured.toString(StandardCharsets.UTF_8);
        assertTrue(printed.contains("SB_TOOLS_FORCE"), printed);
        assertTrue(printed.contains("Available tools:"), printed);
        assertTrue(printed.contains(" - StubTool: A tool used by tests"), printed);
        assertTrue(promptHandler.calls.isEmpty());
        assertTrue(output.calls.isEmpty());
        assertEquals(0, localStorage.flushes.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void entryWithBlankPromptAndNoPromptFileFails(final String prompt) {
        final RuntimeException ex = assertThrows(RuntimeException.class, () -> main().entry(new String[]{prompt}));

        assertEquals("No prompt specified", ex.getMessage());
        assertTrue(promptHandler.calls.isEmpty());
    }

    @Test
    void entryWithNoArgumentsAndNoPromptFileFails() {
        final RuntimeException ex = assertThrows(RuntimeException.class, () -> main().entry(new String[]{}));

        assertEquals("No prompt specified", ex.getMessage());
    }

    @Test
    void entryReadsPromptFromConfiguredFileInOutputDirectory() throws IOException {
        Files.writeString(tempDir.resolve("prompt.txt"), "Prompt from file");
        TestConfigUtil.registerConfig(Map.of(
                "sb.input.file", "prompt.txt",
                "sb.output.directory", tempDir.toString()));
        promptHandler.respondWith(prompts -> new PromptResponseSimple("ok"));

        main().entry(new String[]{"ignored command line prompt"});

        assertEquals(List.of("Prompt from file"), promptHandler.calls.get(0).prompts());
    }

    @Test
    void entryReadsPromptFileWhenNoArgumentsAreSupplied() throws IOException {
        Files.writeString(tempDir.resolve("prompt.txt"), "Only from file");
        TestConfigUtil.registerConfig(Map.of(
                "sb.input.file", "prompt.txt",
                "sb.output.directory", tempDir.toString()));
        promptHandler.respondWith(prompts -> new PromptResponseSimple("ok"));

        main().entry(new String[]{});

        assertEquals(List.of("Only from file"), promptHandler.calls.get(0).prompts());
    }

    @Test
    void entryWithBlankPromptFileSettingUsesCommandLinePrompt() {
        TestConfigUtil.registerConfig(Map.of("sb.input.file", " "));
        promptHandler.respondWith(prompts -> new PromptResponseSimple("ok"));

        main().entry(new String[]{"command line prompt"});

        assertEquals(List.of("command line prompt"), promptHandler.calls.get(0).prompts());
    }

    @Test
    void entryFailsWhenConfiguredPromptFileIsMissing() {
        TestConfigUtil.registerConfig(Map.of(
                "sb.input.file", "missing.txt",
                "sb.output.directory", tempDir.toString()));

        assertThrows(NoSuchFileException.class, () -> main().entry(new String[]{"prompt"}));
        assertTrue(promptHandler.calls.isEmpty());
    }

    @Test
    void entryWithPromptFormatAndContextPassesContextToHandlerAndOutputs() {
        promptHandler.respondWith(prompts -> new PromptResponseSimple("answer"));
        final Map<String, String> context = Map.of("tool", "ZenDesk");

        final PromptHandlerResponse response = main().entry("single prompt", "upper", context);

        assertEquals("ANSWER", response.getResponseText());
        assertEquals(List.of(new HandlerCall(context, List.of("single prompt"))), promptHandler.calls);
        assertEquals(ALL_OUTPUTS, output.methodNames());
        assertTrue(output.calls.stream().allMatch(call -> call.context().equals(context)));
    }

    @Test
    @SuppressWarnings("NullAway")
    void entryWithNullPromptFormatAndContextUsesSafeDefaults() {
        promptHandler.respondWith(prompts -> new PromptResponseSimple("answer"));

        final PromptHandlerResponse response = main().entry((String) null, null, null);

        assertEquals("answer", response.getResponseText());
        assertEquals(List.of(new HandlerCall(Map.of(), List.of(""))), promptHandler.calls);
        assertEquals(1, localStorage.flushes.get());
    }

    @Test
    @SuppressWarnings("NullAway")
    void entryWithPromptListDropsNullPrompts() {
        promptHandler.respondWith(prompts -> new PromptResponseSimple("answer"));

        main().entry(Arrays.asList("first", null, "second"), "no-op", Map.of());

        assertEquals(List.of("first", "second"), promptHandler.calls.get(0).prompts());
    }

    @Test
    @SuppressWarnings("NullAway")
    void entryWithNullPromptListSendsNoPrompts() {
        promptHandler.respondWith(prompts -> new PromptResponseSimple("answer"));

        main().entry((List<String>) null, null, null);

        assertEquals(List.of(new HandlerCall(Map.of(), List.of())), promptHandler.calls);
    }

    @Test
    void entryRethrowsHandlerFailureWithoutWritingOutputsButStillFlushesCache() {
        promptHandler.respondWith(prompts -> {
            throw new IllegalStateException("handler failed");
        });

        final IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> main().entry(List.of("prompt"), "no-op", Map.of()));

        assertEquals("handler failed", ex.getMessage());
        assertTrue(output.calls.isEmpty());
        assertEquals(1, localStorage.flushes.get());
    }

    record HandlerCall(Map<String, String> context, List<String> prompts) {
    }

    record OutputCall(String method, PromptHandlerResponse response, Map<String, String> context) {
    }

    static final class RecordingPromptHandler implements PromptHandler {
        final List<HandlerCall> calls = Collections.synchronizedList(new ArrayList<>());
        private Function<List<String>, PromptHandlerResponse> responder = prompts -> new PromptResponseSimple("");

        void respondWith(final Function<List<String>, PromptHandlerResponse> responder) {
            this.responder = responder;
        }

        @Override
        public PromptHandlerResponse handlePrompt(final Map<String, String> context, final List<String> prompts) {
            calls.add(new HandlerCall(Map.copyOf(context), List.copyOf(prompts)));
            return responder.apply(prompts);
        }
    }

    /**
     * Records the outputs written with a context. The overloads without a context are not used by Main.
     */
    static final class RecordingOutput implements PromptHandlerOutput {
        final List<OutputCall> calls = Collections.synchronizedList(new ArrayList<>());

        List<String> methodNames() {
            return calls.stream().map(OutputCall::method).toList();
        }

        private void record(final String method, final PromptHandlerResponse content, final Map<String, String> context) {
            calls.add(new OutputCall(method, content, context));
        }

        @Override
        public void printOutput(final PromptHandlerResponse content) {
            record("printOutput", content, Map.of("overload", "none"));
        }

        @Override
        public void writeAnnotations(final PromptHandlerResponse content) {
            record("writeAnnotations", content, Map.of("overload", "none"));
        }

        @Override
        public void writeLinks(final PromptHandlerResponse content) {
            record("writeLinks", content, Map.of("overload", "none"));
        }

        @Override
        public void writeDebug(final PromptHandlerResponse content) {
            record("writeDebug", content, Map.of("overload", "none"));
        }

        @Override
        public void writeOutput(final PromptHandlerResponse content) {
            record("writeOutput", content, Map.of("overload", "none"));
        }

        @Override
        public void saveMetadata(final PromptHandlerResponse content) {
            record("saveMetadata", content, Map.of("overload", "none"));
        }

        @Override
        public void saveIntermediateResults(final PromptHandlerResponse content) {
            record("saveIntermediateResults", content, Map.of("overload", "none"));
        }

        @Override
        public void printOutput(final PromptHandlerResponse content, final Map<String, String> context) {
            record("printOutput", content, context);
        }

        @Override
        public void writeAnnotations(final PromptHandlerResponse content, final Map<String, String> context) {
            record("writeAnnotations", content, context);
        }

        @Override
        public void writeLinks(final PromptHandlerResponse content, final Map<String, String> context) {
            record("writeLinks", content, context);
        }

        @Override
        public void writeDebug(final PromptHandlerResponse content, final Map<String, String> context) {
            record("writeDebug", content, context);
        }

        @Override
        public void writeOutput(final PromptHandlerResponse content, final Map<String, String> context) {
            record("writeOutput", content, context);
        }

        @Override
        public void saveMetadata(final PromptHandlerResponse content, final Map<String, String> context) {
            record("saveMetadata", content, context);
        }

        @Override
        public void saveIntermediateResults(final PromptHandlerResponse content, final Map<String, String> context) {
            record("saveIntermediateResults", content, context);
        }
    }

    static final class FlushCountingLocalStorage extends MockLocalStorage {
        final AtomicInteger flushes = new AtomicInteger();

        @Override
        public void flush() {
            flushes.incrementAndGet();
        }
    }

    static final class StubTool implements Tool<Void> {
        @Override
        public String getName() {
            return "StubTool";
        }

        @Override
        public String getDescription() {
            return "A tool used by tests";
        }

        @Override
        public List<ToolArguments> getArguments() {
            return List.of();
        }

        @Override
        public List<RagDocumentContext<Void>> getContext(final Map<String, String> environmentSettings, final List<String> prompts, final List<ToolArgs> arguments) {
            return List.of();
        }

        @Override
        public RagMultiDocumentContext<Void> call(final Map<String, String> environmentSettings, final List<String> prompts, final List<ToolArgs> arguments) {
            return new RagMultiDocumentContext<>(prompts);
        }

        @Override
        public int contextHashCode(final Map<String, String> environmentSettings, final List<String> prompts, final List<ToolArgs> arguments) {
            return 0;
        }

        @Override
        public String getContextLabel() {
            return "Stub";
        }
    }
}
