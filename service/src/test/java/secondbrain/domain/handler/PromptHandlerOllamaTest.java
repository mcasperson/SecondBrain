package secondbrain.domain.handler;

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
import org.junit.jupiter.params.provider.ValueSource;
import secondbrain.domain.context.CosineSimilarityCalculator;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.context.RagStringContext;
import secondbrain.domain.context.SentenceVectorizer;
import secondbrain.domain.context.SimpleSentenceSplitter;
import secondbrain.domain.context.Vector;
import secondbrain.domain.exceptionhandling.LoggingExceptionHandler;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.json.JsonDeserializerJackson;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.test.FixedClientConstructor;
import secondbrain.domain.test.RecordingLlmClient;
import secondbrain.domain.test.TestConfigUtil;
import secondbrain.domain.toolbuilder.ToolBuilderLlama3;
import secondbrain.domain.toolbuilder.ToolSelector;
import secondbrain.domain.tooldefs.IntermediateResult;
import secondbrain.domain.tooldefs.MetaObjectResult;
import secondbrain.domain.tooldefs.MetaObjectResults;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.validate.ValidateListEmptyOrNull;
import secondbrain.domain.web.ClientConstructor;
import secondbrain.infrastructure.llm.LlmClient;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(PromptHandlerOllama.class)
@AddBeanClasses(ToolSelector.class)
@AddBeanClasses(ToolBuilderLlama3.class)
@AddBeanClasses(JsonDeserializerJackson.class)
@AddBeanClasses(ValidateListEmptyOrNull.class)
@AddBeanClasses(LoggingExceptionHandler.class)
@AddBeanClasses(SimpleSentenceSplitter.class)
@AddBeanClasses(CosineSimilarityCalculator.class)
@AddBeanClasses(Loggers.class)
class PromptHandlerOllamaTest {
    private static final String NL = System.lineSeparator();
    private static final String PROMPT = "When does the rollout start?";
    private static final String TOOL = "Echo";
    private static final int RETRIES = 10;
    /**
     * Selecting the Echo tool through the context skips the LLM tool selection.
     */
    private static final Map<String, String> FORCE_TOOL = Map.of("tool", TOOL);

    private final RecordingLlmClient llmClient = new RecordingLlmClient();
    private final ScriptedTool tool = new ScriptedTool();
    private final Map<String, String> settings = new HashMap<>();

    @Inject
    private PromptHandlerOllama handler;

    @Produces
    Tool<Void> produceTool() {
        return tool;
    }

    @Produces
    @Preferred
    LlmClient produceLlmClient() {
        return llmClient;
    }

    @Produces
    ClientConstructor produceClientConstructor() {
        return new FixedClientConstructor(mock(Client.class));
    }

    @Produces
    SentenceVectorizer produceSentenceVectorizer() {
        return new KeywordVectorizer();
    }

    @BeforeEach
    void setUp() {
        TestConfigUtil.registerConfig(settings);
    }

    /**
     * Registers a configuration value. Must be called before the handler is first used, as CDI creates it lazily.
     */
    private void configure(final String key, final String value) {
        settings.put(key, value);
        TestConfigUtil.registerConfig(settings);
    }

    private static RagDocumentContext<Void> doc(final String id, final String document, final String link, final List<String> keywords) {
        return new RagDocumentContext<>(TOOL, "Label", document,
                List.of(new RagStringContext(document, KeywordVectorizer.vector(document))), id, null, link, keywords);
    }

    private static RagMultiDocumentContext<Void> answer(final String response, final List<RagDocumentContext<Void>> docs) {
        return new RagMultiDocumentContext<>(List.of(PROMPT), "instructions", docs).updateResponse(response);
    }

    @Test
    void forcedToolIsCalledWithTheContextAndPrompts() {
        tool.respond = call -> answer("It starts in March.", List.of());
        final Map<String, String> context = Map.of("tool", TOOL, "argument_debugging", "true");

        final PromptHandlerResponse response = handler.handlePrompt(context, List.of(PROMPT, "Second prompt"));

        assertEquals("It starts in March.", response.getResponseText());
        assertEquals("", response.getAnnotations());
        assertEquals("", response.getLinks());
        assertEquals("", response.getDebugInfo());
        assertEquals(List.of(new ScriptedTool.Call(context, List.of(PROMPT, "Second prompt"), List.of())), tool.calls);
        // Forcing a tool skips the LLM
        assertTrue(llmClient.requests.isEmpty());
    }

    @Test
    void llmSelectsTheToolAndItsArguments() {
        llmClient.respondWith(doc -> "[{\"toolName\": \"Echo\", \"toolArgs\": [{\"argName\": \"month\", \"argValue\": \"March\"}]}]");
        tool.respond = call -> answer("Selected.", List.of());

        final PromptHandlerResponse response = handler.handlePrompt(Map.of(), List.of(PROMPT));

        assertEquals("Selected.", response.getResponseText());
        assertEquals(List.of(new ToolArgs("month", "March", false)), tool.calls.getFirst().arguments());
        assertEquals(1, llmClient.requests.size());
        // The tool selection prompt is built from the first prompt
        assertTrue(llmClient.requests.getFirst().document().getPrompts().getFirst().contains(PROMPT));
    }

    @Test
    void emptyPromptsSelectTheToolWithAnEmptyPrompt() {
        tool.respond = call -> answer("No prompt.", List.of());

        final PromptHandlerResponse response = handler.handlePrompt(FORCE_TOOL, List.of());

        assertEquals("No prompt.", response.getResponseText());
        assertEquals(List.of(), tool.calls.getFirst().prompts());
    }

    @Test
    void unknownForcedToolReportsThatNoToolWasFound() {
        final PromptHandlerResponse response = handler.handlePrompt(Map.of("tool", "Missing"), List.of(PROMPT));

        assertEquals("No tool found", response.getResponseText());
        assertTrue(tool.calls.isEmpty());
    }

    @Test
    void unknownLlmSelectedToolReportsThatNoToolWasFound() {
        llmClient.respondWith(doc -> "[{\"toolName\": \"Missing\", \"toolArgs\": []}]");

        final PromptHandlerResponse response = handler.handlePrompt(Map.of(), List.of(PROMPT));

        assertEquals("No tool found", response.getResponseText());
        assertEquals(1, llmClient.requests.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "[]"})
    void toolSelectionIsRetriedUntilTheRetryLimit(final String llmResponse) {
        llmClient.respondWith(doc -> llmResponse);

        final PromptHandlerResponse response = handler.handlePrompt(Map.of(), List.of(PROMPT));

        assertEquals(RETRIES, llmClient.requests.size());
        assertFalse(response.getResponseText().isBlank());
        assertTrue(tool.calls.isEmpty());
    }

    @Test
    void emptyToolSelectionsAreRetriedThenReported() {
        // EmptyList is an InternalException but not an InternalFailure, so it is retried like an external error
        llmClient.respondWith(doc -> "[]");

        final PromptHandlerResponse response = handler.handlePrompt(Map.of(), List.of(PROMPT));

        assertEquals("secondbrain.domain.exceptions.EmptyList: List is empty", response.getResponseText());
        assertEquals(RETRIES, llmClient.requests.size());
    }

    @Test
    void toolSelectionRecoversOnALaterAttempt() {
        final int[] attempts = {0};
        llmClient.respondWith(doc -> ++attempts[0] < 3 ? "invalid" : "[{\"toolName\": \"Echo\", \"toolArgs\": []}]");
        tool.respond = call -> answer("Third time lucky.", List.of());

        final PromptHandlerResponse response = handler.handlePrompt(Map.of(), List.of(PROMPT));

        assertEquals("Third time lucky.", response.getResponseText());
        assertEquals(3, llmClient.requests.size());
        assertEquals(1, tool.calls.size());
    }

    @Test
    void internalFailuresFromTheToolAreReportedWithoutRetrying() {
        tool.respond = call -> {
            throw new InternalFailure("Missing company ID");
        };

        final PromptHandlerResponse response = handler.handlePrompt(FORCE_TOOL, List.of(PROMPT));

        assertEquals("secondbrain.domain.exceptions.InternalFailure: Missing company ID", response.getResponseText());
        assertEquals(1, tool.calls.size());
    }

    @Test
    void otherToolFailuresAreRetriedThenReported() {
        tool.respond = call -> {
            throw new IllegalStateException("Service unavailable");
        };

        final PromptHandlerResponse response = handler.handlePrompt(FORCE_TOOL, List.of(PROMPT));

        assertEquals("java.lang.IllegalStateException: Service unavailable", response.getResponseText());
        assertEquals(RETRIES, tool.calls.size());
        assertEquals(List.of(), response.getMetaObjectResults());
    }

    @Test
    void toolFailuresStopBeingRetriedOnceTheToolSucceeds() {
        tool.respond = call -> {
            if (tool.calls.size() < 2) {
                throw new IllegalStateException("Flaky");
            }
            return answer("Recovered.", List.of());
        };

        assertEquals("Recovered.", handler.handlePrompt(FORCE_TOOL, List.of(PROMPT)).getResponseText());
        assertEquals(2, tool.calls.size());
    }

    @Test
    void responsesAreAnnotatedWithMatchingContextSentences() {
        configure("sb.annotation.minwords", "2");
        tool.respond = call -> answer("The rollout starts in March. Unrelated words here.",
                List.of(doc("doc-1", "The rollout begins in March", "[Doc](https://example.com/doc)", List.of())));

        final PromptHandlerResponse response = handler.handlePrompt(FORCE_TOOL, List.of(PROMPT));

        assertEquals("The rollout starts in March [1]. Unrelated words here.", response.getResponseText());
        assertEquals(NL + NL + "References:" + NL + NL + "* [1]: The rollout begins in March (doc-1)", response.getAnnotations());
    }

    @Test
    void invalidAnnotationSettingsUseTheDefaults() {
        // The default minimum of 10 words excludes the short response sentence from annotation
        configure("sb.annotation.minwords", "few");
        configure("sb.annotation.minsimilarity", "high");
        tool.respond = call -> answer("The rollout starts in March.",
                List.of(doc("doc-1", "The rollout begins in March", "", List.of())));

        final PromptHandlerResponse response = handler.handlePrompt(FORCE_TOOL, List.of(PROMPT));

        assertEquals("The rollout starts in March.", response.getResponseText());
        assertEquals("", response.getAnnotations());
    }

    @Test
    void minimumSimilarityExcludesWeakMatches() {
        configure("sb.annotation.minwords", "2");
        configure("sb.annotation.minsimilarity", "1.1");
        tool.respond = call -> answer("The rollout starts in March.",
                List.of(doc("doc-1", "The rollout begins in March", "", List.of())));

        assertEquals("", handler.handlePrompt(FORCE_TOOL, List.of(PROMPT)).getAnnotations());
    }

    @Test
    void annotationsCanBeDisabled() {
        configure("sb.annotation.minwords", "2");
        configure("sb.output.disableAnnotations", "true");
        tool.respond = call -> answer("The rollout starts in March.",
                List.of(doc("doc-1", "The rollout begins in March", "[Doc](https://example.com/doc)", List.of())));

        final PromptHandlerResponse response = handler.handlePrompt(FORCE_TOOL, List.of(PROMPT));

        assertEquals("The rollout starts in March.", response.getResponseText());
        assertEquals("", response.getAnnotations());
        assertEquals(NL + NL + NL + "* [Doc](https://example.com/doc)", response.getLinks());
    }

    @Test
    void linksListEachDocumentWithItsKeywords() {
        tool.respond = call -> answer("Answer.", List.of(
                doc("doc-1", "First document", "[One](https://example.com/1)", List.of("rollout", "march")),
                doc("doc-2", "Second document", "[Two](https://example.com/2)", List.of()),
                doc("doc-3", "Third document", "", List.of("ignored"))));

        final PromptHandlerResponse response = handler.handlePrompt(FORCE_TOOL, List.of(PROMPT));

        // Documents without a link are left out
        assertEquals(NL + NL + NL + "* [One](https://example.com/1) (Keywords: rollout, march)" + NL + "* [Two](https://example.com/2)",
                response.getLinks());
    }

    @Test
    void linksAreEmptyWhenNoDocumentHasALink() {
        tool.respond = call -> answer("Answer.", List.of(
                new RagDocumentContext<>(TOOL, "Label", "No link", List.of(), "doc-1", null, null, List.of())));

        assertEquals("", handler.handlePrompt(FORCE_TOOL, List.of(PROMPT)).getLinks());
    }

    @Test
    void blankLinksStillAddALinksSection() {
        // Looks like a bug: blank (but not null) links count as links, so an empty links section is returned
        tool.respond = call -> answer("Answer.", List.of(doc("doc-1", "Document", "", List.of())));

        assertEquals(NL + NL, handler.handlePrompt(FORCE_TOOL, List.of(PROMPT)).getLinks());
    }

    @Test
    void linksCanBeDisabled() {
        configure("sb.output.disableLinks", "true");
        tool.respond = call -> answer("Answer.", List.of(doc("doc-1", "Document", "[One](https://example.com/1)", List.of())));

        assertEquals("", handler.handlePrompt(FORCE_TOOL, List.of(PROMPT)).getLinks());
    }

    @Test
    void linksCanBeDisabledWhenAnnotationsAreDisabled() {
        configure("sb.output.disableAnnotations", "true");
        configure("sb.output.disableLinks", "true");
        configure("sb.tools.debug", "true");
        tool.respond = call -> new RagMultiDocumentContext<>(List.of(PROMPT), "instructions",
                List.of(doc("doc-1", "Document", "[One](https://example.com/1)", List.of())), "debug details").updateResponse("Answer.");

        final PromptHandlerResponse response = handler.handlePrompt(FORCE_TOOL, List.of(PROMPT));

        assertEquals("", response.getLinks());
        assertEquals(NL + NL + "Debug:" + NL + "debug details", response.getDebugInfo());
    }

    @Test
    void debugInformationIsIncludedWhenEnabled() {
        configure("sb.tools.debug", "true");
        tool.respond = call -> new RagMultiDocumentContext<Void>(List.of(PROMPT), "instructions", List.of(), "debug details").updateResponse("Answer.");

        assertEquals(NL + NL + "Debug:" + NL + "debug details", handler.handlePrompt(FORCE_TOOL, List.of(PROMPT)).getDebugInfo());
    }

    @Test
    void blankDebugInformationIsLeftOut() {
        configure("sb.tools.debug", "true");
        tool.respond = call -> new RagMultiDocumentContext<Void>(List.of(PROMPT), "instructions", List.of(), " ").updateResponse("Answer.");

        assertEquals("", handler.handlePrompt(FORCE_TOOL, List.of(PROMPT)).getDebugInfo());
    }

    @Test
    void debugInformationIsLeftOutByDefault() {
        tool.respond = call -> new RagMultiDocumentContext<Void>(List.of(PROMPT), "instructions", List.of(), "debug details").updateResponse("Answer.");

        assertEquals("", handler.handlePrompt(FORCE_TOOL, List.of(PROMPT)).getDebugInfo());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void metadataAndIntermediateResultsArePassedThrough(final boolean disableAnnotations) {
        configure("sb.output.disableAnnotations", Boolean.toString(disableAnnotations));
        final MetaObjectResults docMetadata = new MetaObjectResults(List.of(new MetaObjectResult("rating", 7)), "doc.json", "doc-1");
        final MetaObjectResults overallMetadata = new MetaObjectResults(List.of(new MetaObjectResult("total", 1)), "all.json", "all");
        final IntermediateResult intermediate = new IntermediateResult("raw data", "data.txt");
        tool.respond = call -> new RagMultiDocumentContext<>(List.of(PROMPT), "instructions", List.of(
                doc("doc-1", "Document", "", List.of()).updateMetadata(docMetadata).addIntermediateResult(intermediate)))
                .updateMetadata(overallMetadata)
                .updateResponse("Answer.");

        final PromptHandlerResponse response = handler.handlePrompt(FORCE_TOOL, List.of(PROMPT));

        assertEquals(List.of(docMetadata, overallMetadata), response.getMetaObjectResults());
        assertEquals(List.of(intermediate), response.getIntermediateResults());
    }

    /**
     * Maps text to a vector from the words "rollout" and "march", so sentences mentioning both are identical.
     */
    private static final class KeywordVectorizer implements SentenceVectorizer {
        static Vector vector(final String text) {
            final String lower = text.toLowerCase(Locale.ROOT);
            return new Vector(lower.contains("rollout") ? 1.0 : 0.0, lower.contains("march") ? 1.0 : 0.0, 0.01);
        }

        @Override
        public RagStringContext vectorize(final String text) {
            return new RagStringContext(text, vector(text));
        }

        @Override
        public RagStringContext vectorize(final String text, final String hiddenText) {
            return vectorize(text);
        }

        @Override
        public List<RagStringContext> vectorize(final List<String> text) {
            return text.stream().map(this::vectorize).toList();
        }

        @Override
        public List<RagStringContext> vectorize(final List<String> text, final String hiddenText) {
            return vectorize(text);
        }
    }

    /**
     * A tool named Echo that records each call and responds with the configured function.
     */
    private static final class ScriptedTool implements Tool<Void> {
        record Call(Map<String, String> context, List<String> prompts, List<ToolArgs> arguments) {
        }

        final List<Call> calls = new CopyOnWriteArrayList<>();
        volatile Function<Call, RagMultiDocumentContext<Void>> respond = call -> answer("", List.of());

        @Override
        public String getName() {
            return TOOL;
        }

        @Override
        public String getDescription() {
            return "Echoes a scripted answer";
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
            final Call call = new Call(environmentSettings, prompts, arguments);
            calls.add(call);
            return respond.apply(call);
        }

        @Override
        public String getContextLabel() {
            return "Echo";
        }

        @Override
        public int contextHashCode(final Map<String, String> environmentSettings, final List<String> prompts, final List<ToolArgs> arguments) {
            return 0;
        }
    }
}
