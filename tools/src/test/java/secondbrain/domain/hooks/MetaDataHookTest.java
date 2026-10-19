package secondbrain.domain.hooks;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.jboss.weld.junit5.auto.ExcludeBeanClasses;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.test.TestConfigUtil;
import secondbrain.domain.tooldefs.MetaObjectResult;
import secondbrain.domain.tooldefs.MetaObjectResults;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tools.rating.RatingTool;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(MetaDataHook.class)
@AddBeanClasses(Loggers.class)
// The rating tool is replaced by a mock from a producer method
@ExcludeBeanClasses(RatingTool.class)
class MetaDataHookTest {
    private static final String TOOL = "SomeTool";
    private static final String REPORT = "meta-report.json";

    /**
     * A call made to the rating tool.
     */
    record RatingCall(Map<String, String> environmentSettings, List<String> prompts, List<ToolArgs> arguments) {
    }

    private final List<RatingCall> ratingCalls = new CopyOnWriteArrayList<>();
    private final Map<String, String> settings = new HashMap<>();
    private final RatingTool ratingTool = mock(RatingTool.class);
    private volatile Function<String, String> ratings = prompt -> "5";

    @Inject
    private MetaDataHook hook;

    @Produces
    RatingTool produceRatingTool() {
        return ratingTool;
    }

    @BeforeEach
    void setUp() {
        TestConfigUtil.registerConfig(settings);
        when(ratingTool.call(any(), any(), any())).thenAnswer(invocation -> {
            final Map<String, String> env = invocation.getArgument(0);
            final List<String> prompts = invocation.getArgument(1);
            final List<ToolArgs> arguments = invocation.getArgument(2);
            ratingCalls.add(new RatingCall(env, prompts, arguments));
            return new RagMultiDocumentContext<Void>(prompts).updateResponse(ratings.apply(prompts.getFirst()));
        });
    }

    /**
     * Registers configuration values. Must be called before the hook is first used, as CDI creates it lazily.
     */
    private void configure(final String... keyValues) {
        for (int i = 0; i < keyValues.length; i += 2) {
            settings.put(keyValues[i], keyValues[i + 1]);
        }
        TestConfigUtil.registerConfig(settings);
    }

    private void field(final int index, final String name, final String prompt) {
        configure("sb.metadatahook.contextMetaField" + index, name, "sb.metadatahook.contextMetaPrompt" + index, prompt);
    }

    private static RagMultiDocumentContext<Void> context(final String... documents) {
        final List<RagDocumentContext<Void>> contexts = new ArrayList<>();
        for (final String document : documents) {
            contexts.add(new RagDocumentContext<>(TOOL, "label", document, List.of()));
        }
        return new RagMultiDocumentContext<Void>(List.of("question"), "instructions", contexts).updateResponse("the answer");
    }

    private static MetaObjectResult result(final String name, final int value) {
        return new MetaObjectResult(name, value, null, "MetaDataHook");
    }

    @Test
    void nameIsTheClassName() {
        assertEquals("MetaDataHook", hook.getName());
    }

    @Test
    void ratesTheCombinedDocumentsForEachConfiguredField() {
        configure("sb.metadatahook.metareport", REPORT);
        field(1, "Sentiment", "Rate the sentiment");
        field(2, "Urgency", "Rate the urgency");
        ratings = prompt -> prompt.equals("Rate the sentiment") ? "8" : "3";
        final RagMultiDocumentContext<Void> input = context("first document", "second document");

        final RagMultiDocumentContext<Void> output = hook.process(TOOL, input);

        final MetaObjectResults metadata = output.getMetadata();
        assertEquals(List.of(result("Sentiment", 8), result("Urgency", 3)), List.copyOf(metadata));
        assertEquals(REPORT, metadata.getFilename());
        assertEquals("", metadata.getId());
        assertEquals(List.of(
                        new RatingCall(Map.of(RatingTool.RATING_DOCUMENT_CONTEXT_ARG, "first document\nsecond document"), List.of("Rate the sentiment"), List.of()),
                        new RatingCall(Map.of(RatingTool.RATING_DOCUMENT_CONTEXT_ARG, "first document\nsecond document"), List.of("Rate the urgency"), List.of())),
                ratingCalls);
        // Only the metadata is changed
        assertEquals("the answer", output.getResponse());
        assertEquals(input.getIndividualContexts(), output.getIndividualContexts());
    }

    @Test
    void fieldsAreRatedInNumericOrderUpToTwenty() {
        configure("sb.metadatahook.metareport", REPORT);
        field(20, "Last", "last prompt");
        field(10, "Middle", "middle prompt");
        field(1, "First", "first prompt");

        final RagMultiDocumentContext<Void> output = hook.process(TOOL, context("doc"));

        assertEquals(List.of("First", "Middle", "Last"), output.getMetadata().stream().map(MetaObjectResult::name).toList());
        assertEquals(List.of("first prompt", "middle prompt", "last prompt"), ratingCalls.stream().map(c -> c.prompts().getFirst()).toList());
    }

    @Test
    void fieldsWithoutAPromptOrNameAreIgnored() {
        configure("sb.metadatahook.metareport", REPORT,
                "sb.metadatahook.contextMetaField1", "NoPrompt",
                "sb.metadatahook.contextMetaPrompt2", "no name");
        field(3, "Complete", "complete prompt");

        final RagMultiDocumentContext<Void> output = hook.process(TOOL, context("doc"));

        assertEquals(List.of(result("Complete", 5)), List.copyOf(output.getMetadata()));
        assertEquals(1, ratingCalls.size());
    }

    @Test
    void contextIsUnchangedWithoutCompleteFields() {
        configure("sb.metadatahook.metareport", REPORT, "sb.metadatahook.contextMetaField1", "NoPrompt");
        final RagMultiDocumentContext<Void> input = context("doc");

        assertSame(input, hook.process(TOOL, input));
        assertTrue(ratingCalls.isEmpty());
    }

    @Test
    void contextIsUnchangedWithoutAMetaReport() {
        field(1, "Sentiment", "Rate the sentiment");
        final RagMultiDocumentContext<Void> input = context("doc");

        assertSame(input, hook.process(TOOL, input));
        assertTrue(ratingCalls.isEmpty());
    }

    @Test
    void anEmptyContextIsStillRated() {
        configure("sb.metadatahook.metareport", REPORT);
        field(1, "Sentiment", "Rate the sentiment");

        final RagMultiDocumentContext<Void> output = hook.process(TOOL, context());

        assertEquals(List.of(result("Sentiment", 5)), List.copyOf(output.getMetadata()));
        assertEquals(Map.of(RatingTool.RATING_DOCUMENT_CONTEXT_ARG, ""), ratingCalls.getFirst().environmentSettings());
    }

    @ParameterizedTest
    @CsvSource({"' 7 ', 7", "0, 0", "-2, -2", "high, 0", "'', 0", "7.5, 0"})
    void ratingsAreParsedAsIntegersWithAFallbackOfZero(final String rating, final int expected) {
        configure("sb.metadatahook.metareport", REPORT);
        field(1, "Sentiment", "Rate the sentiment");
        ratings = prompt -> rating;

        assertEquals(List.of(result("Sentiment", expected)), List.copyOf(hook.process(TOOL, context("doc")).getMetadata()));
    }

    @Test
    void ratingFailuresAreRecordedAsZeroAndDoNotStopOtherFields() {
        configure("sb.metadatahook.metareport", REPORT);
        field(1, "Broken", "broken prompt");
        field(2, "Working", "working prompt");
        ratings = prompt -> {
            if (prompt.equals("broken prompt")) {
                throw new IllegalStateException("rating failed");
            }
            return "9";
        };

        assertEquals(List.of(result("Broken", 0), result("Working", 9)), List.copyOf(hook.process(TOOL, context("doc")).getMetadata()));
    }

    @Test
    void missingRatingResponsesAreRecordedAsZero() {
        configure("sb.metadatahook.metareport", REPORT);
        field(1, "Sentiment", "Rate the sentiment");
        doAnswer(invocation -> new RagMultiDocumentContext<Void>("prompt").updateResponses(List.of())).when(ratingTool).call(any(), any(), any());

        assertEquals(List.of(result("Sentiment", 0)), List.copyOf(hook.process(TOOL, context("doc")).getMetadata()));
    }
}
