package secondbrain.domain.processing;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import secondbrain.domain.args.ArgsAccessorSimple;
import secondbrain.domain.concurrency.SharedVirtualThreadExecutor;
import secondbrain.domain.config.LocalConfigFilteredItem;
import secondbrain.domain.context.HashMapEnvironmentSettings;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.exceptionhandling.StandardExceptionMapping;
import secondbrain.domain.hooks.NamedHooksContainer;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.logger.Loggers;
import secondbrain.domain.objects.ToStringGenerator;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.sanitize.GetFirstDigits;
import secondbrain.domain.sanitize.GetFirstMarkdownBlock;
import secondbrain.domain.test.RecordingLlmClient;
import secondbrain.domain.test.RecordingLocalStorage;
import secondbrain.domain.test.TestArgs;
import secondbrain.domain.test.TestConfigUtil;
import secondbrain.domain.tooldefs.IntermediateResult;
import secondbrain.domain.tooldefs.MetaObjectResult;
import secondbrain.domain.tooldefs.MetaObjectResults;
import secondbrain.domain.tools.rating.RatingTool;
import secondbrain.domain.validate.ValidateListEmptyOrNull;
import secondbrain.domain.validate.ValidateStringBlank;
import secondbrain.infrastructure.llm.LlmClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link RatingToolRatingMetadata} with a real {@link RatingTool}. The LLM is a recording test instance, and the
 * cache always misses unless a cached rating is primed.
 */
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(RatingToolRatingMetadata.class)
@AddBeanClasses(RatingTool.class)
@AddBeanClasses(NamedHooksContainer.class)
@AddBeanClasses(ArgsAccessorSimple.class)
@AddBeanClasses(ValidateListEmptyOrNull.class)
@AddBeanClasses(GetFirstMarkdownBlock.class)
@AddBeanClasses(GetFirstDigits.class)
@AddBeanClasses(StandardExceptionMapping.class)
@AddBeanClasses(SharedVirtualThreadExecutor.class)
@AddBeanClasses(Loggers.class)
@AddBeanClasses(ValidateStringBlank.class)
class RatingToolRatingMetadataTest {
    private static final String TOOL = "ZenDesk";
    private static final String QUESTION = "Is this about billing?";
    private static final String RATING_CACHE_SOURCE = "RatingToolV2";
    private static final int DEFAULT_RATING = 3;

    private final RecordingLlmClient llmClient = new RecordingLlmClient();
    private final RecordingLocalStorage localStorage = new RecordingLocalStorage();

    @Inject
    @Preferred
    private RatingMetadata ratingMetadata;

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
    ToStringGenerator produceToStringGenerator() {
        return TestArgs.toStringGenerator("rating-args", 11);
    }

    @BeforeEach
    void setUp() {
        TestConfigUtil.registerConfig(Map.of());
    }

    private static LocalConfigFilteredItem filter(final String question) {
        return new LocalConfigFilteredItem() {
            @Override
            public String getContextFilterQuestion() {
                return question;
            }

            @Override
            public Integer getDefaultRating() {
                return DEFAULT_RATING;
            }
        };
    }

    private static RagDocumentContext<Void> activity() {
        return new RagDocumentContext<>("SourceTool", "Ticket", "ticket text", List.of(), "doc-1");
    }

    private static RagMultiDocumentContext<Void> cachedRating(final List<String> responses, final List<RagDocumentContext<Void>> contexts) {
        return new RagMultiDocumentContext<Void>(List.of(QUESTION), "", contexts).updateResponses(responses);
    }

    @Test
    void ratingFromLlmIsReturnedAsFilterRatingMetadata() {
        llmClient.respondWith(document -> "7");

        final RatingResults results = ratingMetadata.getMetadata(TOOL, Map.of(), activity(), filter(QUESTION)).orElseThrow();

        final MetaObjectResults meta = results.getMetadata();
        assertEquals(List.of(new MetaObjectResult(RatingToolRatingMetadata.FILTER_RATING_META, 7, "doc-1", TOOL)), List.copyOf(meta));
        assertEquals("ZenDesk-doc-1.json", meta.getFilename());
        assertEquals("doc-1", meta.getId());
        assertEquals(List.of(new IntermediateResult("7", "Rating-SourceTool-doc-1-.txt")), results.getIntermediateResults());
    }

    @Test
    void ratingToolIsCalledWithQuestionAndDocumentContext() {
        llmClient.respondWith(document -> "5");

        ratingMetadata.getMetadata(TOOL, Map.of("existing", "value"), activity(), filter(QUESTION));

        assertEquals(1, llmClient.requests.size());
        final RecordingLlmClient.Request request = llmClient.requests.get(0);
        assertEquals("RatingTool", request.tool());
        assertEquals(List.of(QUESTION), request.document().getPrompts());
        assertEquals("ticket text", request.document().getIndividualContexts().get(0).document());
        assertEquals("value", request.environmentSettings().get("existing"));
        assertEquals("ticket text", request.environmentSettings().get(RatingTool.RATING_DOCUMENT_CONTEXT_ARG));
        assertEquals("doc-1", request.environmentSettings().get(RatingTool.RATING_ID_CONTEXT_ARG));
        assertEquals("SourceTool", request.environmentSettings().get(RatingTool.RATING_TOOL_CONTEXT_ARG));
        assertEquals("ZenDesk[doc-1]", request.environmentSettings().get(HashMapEnvironmentSettings.TOOL_CALLS));
    }

    @Test
    void existingToolCallChainIsExtended() {
        llmClient.respondWith(document -> "5");

        ratingMetadata.getMetadata(TOOL, Map.of(HashMapEnvironmentSettings.TOOL_CALLS, "Parent[p-1]"), activity(), filter(QUESTION));

        assertEquals("Parent[p-1]->ZenDesk[doc-1]",
                llmClient.requests.get(0).environmentSettings().get(HashMapEnvironmentSettings.TOOL_CALLS));
    }

    @Test
    @SuppressWarnings("NullAway")
    void missingActivityIdAndToolUsePlaceholders() {
        llmClient.respondWith(document -> "6");
        final RagDocumentContext<Void> anonymous = new RagDocumentContext<>(null, "Ticket", "anonymous text", List.of(), null);

        final RatingResults results = ratingMetadata.getMetadata(TOOL, Map.of(), anonymous, filter(QUESTION)).orElseThrow();

        final Map<String, String> env = llmClient.requests.get(0).environmentSettings();
        assertEquals("unknownId", env.get(RatingTool.RATING_ID_CONTEXT_ARG));
        assertEquals("unknownTool", env.get(RatingTool.RATING_TOOL_CONTEXT_ARG));
        assertEquals("ZenDesk[unknownId]", env.get(HashMapEnvironmentSettings.TOOL_CALLS));
        assertEquals(List.of(new MetaObjectResult(RatingToolRatingMetadata.FILTER_RATING_META, 6, null, TOOL)), List.copyOf(results.getMetadata()));
        assertEquals("ZenDesk-.json", results.getMetadata().getFilename());
        assertEquals("", results.getMetadata().getId());
        assertEquals(List.of(new IntermediateResult("6", "Rating-unknownTool-unknownId-.txt")), results.getIntermediateResults());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankFilterQuestionSkipsRating(final String question) {
        final Optional<RatingResults> results = ratingMetadata.getMetadata(TOOL, Map.of(), activity(), filter(question));

        assertTrue(results.isEmpty());
        assertTrue(llmClient.requests.isEmpty());
        assertTrue(localStorage.calls.isEmpty());
    }

    @Test
    void cachedRatingIsTrimmedAndIntermediateResultsFromAllContextsAreMerged() {
        localStorage.prime(RATING_CACHE_SOURCE, cachedRating(
                List.of(" 8 \n"),
                List.of(
                        new RagDocumentContext<Void>("RatingTool", "Document", "a", List.of())
                                .addIntermediateResult(new IntermediateResult("first", "first.txt")),
                        new RagDocumentContext<Void>("RatingTool", "Document", "b", List.of())
                                .addIntermediateResult(new IntermediateResult("second", "second.txt")))));

        final RatingResults results = ratingMetadata.getMetadata(TOOL, Map.of(), activity(), filter(QUESTION)).orElseThrow();

        assertEquals(Optional.of(8), results.getMetadata().getIntValueByName(RatingToolRatingMetadata.FILTER_RATING_META));
        assertEquals(List.of(new IntermediateResult("first", "first.txt"), new IntermediateResult("second", "second.txt")),
                results.getIntermediateResults());
        assertTrue(llmClient.requests.isEmpty());
    }

    @Test
    void nonNumericRatingFallsBackToDefaultRatingWithoutIntermediateResults() {
        localStorage.prime(RATING_CACHE_SOURCE, cachedRating(
                List.of("eight"),
                List.of(new RagDocumentContext<Void>("RatingTool", "Document", "a", List.of())
                        .addIntermediateResult(new IntermediateResult("ignored", "ignored.txt")))));

        final RatingResults results = ratingMetadata.getMetadata(TOOL, Map.of(), activity(), filter(QUESTION)).orElseThrow();

        assertEquals(List.of(new MetaObjectResult(RatingToolRatingMetadata.FILTER_RATING_META, DEFAULT_RATING, "doc-1", TOOL)),
                List.copyOf(results.getMetadata()));
        assertEquals(List.of(), results.getIntermediateResults());
    }

    @Test
    void missingRatingResponseFallsBackToDefaultRating() {
        localStorage.prime(RATING_CACHE_SOURCE, cachedRating(List.of(), List.of()));

        final RatingResults results = ratingMetadata.getMetadata(TOOL, Map.of(), activity(), filter(QUESTION)).orElseThrow();

        assertEquals(Optional.of(DEFAULT_RATING), results.getMetadata().getIntValueByName(RatingToolRatingMetadata.FILTER_RATING_META));
    }

    @Test
    void failedLlmCallFallsBackToDefaultRating() {
        llmClient.respondWith(document -> {
            throw new IllegalStateException("LLM down");
        });

        final RatingResults results = ratingMetadata.getMetadata(TOOL, Map.of(), activity(), filter(QUESTION)).orElseThrow();

        assertEquals(Optional.of(DEFAULT_RATING), results.getMetadata().getIntValueByName(RatingToolRatingMetadata.FILTER_RATING_META));
        assertEquals(List.of(), results.getIntermediateResults());
    }

    @Test
    void ratingToolFailureSentinelFallsBackToDefaultRating() {
        localStorage.prime(RATING_CACHE_SOURCE, cachedRating(List.of("-1"), List.of()));

        final RatingResults results = ratingMetadata.getMetadata(TOOL, Map.of(), activity(), filter(QUESTION)).orElseThrow();

        assertEquals(Optional.of(DEFAULT_RATING), results.getMetadata().getIntValueByName(RatingToolRatingMetadata.FILTER_RATING_META));
    }

    @Test
    void ratingOfZeroIsKeptRatherThanReplacedByDefaultRating() {
        localStorage.prime(RATING_CACHE_SOURCE, cachedRating(List.of("0"), List.of()));

        final RatingResults results = ratingMetadata.getMetadata(TOOL, Map.of(), activity(), filter(QUESTION)).orElseThrow();

        assertEquals(Optional.of(0), results.getMetadata().getIntValueByName(RatingToolRatingMetadata.FILTER_RATING_META));
    }
}
