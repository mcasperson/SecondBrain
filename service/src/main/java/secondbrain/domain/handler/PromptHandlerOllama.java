package secondbrain.domain.handler;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.context.*;
import secondbrain.domain.exceptionhandling.ExceptionHandler;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.toolbuilder.ToolSelector;
import secondbrain.domain.tooldefs.ToolCall;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Implementation of the prompt handler.
 */
@ApplicationScoped
public class PromptHandlerOllama implements PromptHandler {
    private static final int TOOL_RETRY = 10;

    @Inject
    @ConfigProperty(name = "sb.annotation.minsimilarity", defaultValue = "0.5")
    private String minSimilarity;

    @Inject
    @ConfigProperty(name = "sb.annotation.minwords", defaultValue = "10")
    private String minWords;

    @Inject
    @ConfigProperty(name = "sb.tools.debug", defaultValue = "false")
    private String debug;

    @Inject
    @ConfigProperty(name = "sb.disablelinks")
    private Optional<String> disableLinks;

    @Inject
    private SimilarityCalculator similarityCalculator;

    @Inject
    private SentenceSplitter sentenceSplitter;

    @Inject
    private SentenceVectorizer sentenceVectorizer;

    @Inject
    private Logger logger;

    @Inject
    private ToolSelector toolSelector;

    @Inject
    private ExceptionHandler exceptionHandler;


    public PromptHandlerResponse handlePrompt(final Map<String, String> context, final String prompt) {
        return handlePromptWithRetry(context, prompt, 1);
    }

    /**
     * Unfortunately the LLMs available to be run locally on most consumer hardware, typically around 8B params
     * quantized to 4 bits, can struggle to generate valid JSON in response to a request to select a tool.
     * So we retry a bunch of times to try and get a valid response.
     */
    public PromptHandlerResponse handlePromptWithRetry(final Map<String, String> context, final String prompt, final int count) {
        return Try.of(() -> toolSelector.getTool(prompt, context))
                .map(toolCall -> callTool(toolCall, context, prompt))
                /*
                    Internal errors are not resolved with retries, so we capture the error message
                    and return it to the user.
                 */
                .recover(InternalFailure.class, ex -> new PromptResponseSimple(exceptionHandler.getExceptionMessage(ex)))
                /*
                    We assume everything else is an external error that may be resolved with retries.
                 */
                .recoverWith(error -> Try.of(() -> {
                    // Selecting the wrong tool can manifest itself as an exception
                    if (count < TOOL_RETRY) {
                        return handlePromptWithRetry(context, prompt, count + 1);
                    }
                    throw error;
                }))
                /*
                    The retry count is exhausted, so we return the error message to the user.
                 */
                .recover(Throwable.class, ex -> new PromptResponseSimple(exceptionHandler.getExceptionMessage(ex)))
                .get();
    }


    private PromptHandlerResponse callTool(@Nullable final ToolCall toolCall, final Map<String, String> context, final String prompt) {
        if (toolCall == null) {
            return new PromptResponseSimple("No tool found");
        }

        logger.log(Level.INFO, "Calling tool " + toolCall.tool().getName());

        final float parsedMinSimilarity = Try.of(() -> Float.parseFloat(minSimilarity))
                .recover(throwable -> 0.5f)
                .get();

        final int parsedMinWords = Try.of(() -> Integer.parseInt(minWords))
                .recover(throwable -> 10)
                .get();

        final boolean argumentDebugging = Try.of(() -> context.get("argument_debugging"))
                .mapTry(Boolean::parseBoolean)
                .recover(e -> false)
                .get();

        /*
            The tools respond with a RagMultiDocumentContext, which contains the text response from the LLM
            and the context that was used to build the prompt, including things like IDs of the context items,
            URLs, and individual sentences.

            We use this information to generate a standardized output that includes the text response from the LLM,
            links to the context items, and annotations that link the LLM output to the original context.
         */
        return Try.of(() -> toolCall.call(context, prompt))
                .map(document -> generateAnnotatedResponse(
                        document,
                        parsedMinSimilarity,
                        parsedMinWords,
                        argumentDebugging))
                .recover(InternalFailure.class, ex -> new PromptResponseSimple(exceptionHandler.getExceptionMessage(ex)))
                .get();
    }

    private PromptHandlerResponse generateAnnotatedResponse(final RagMultiDocumentContext<?> document, final float parsedMinSimilarity, final int parsedMinWords, final boolean argumentDebugging) {
        final PromptResponse response = new PromptResponse(
                getAnnotations(document, parsedMinSimilarity, parsedMinWords),
                getLinks(document),
                getDebugLinks(document, Boolean.getBoolean(debug) || argumentDebugging));

        final String annotatedResponse = response.annotationResult().annotatedContent() +
                response.annotationResult().annotations() +
                response.links() +
                response.debug() +
                getAnnotationCoverage(response.annotationResult(), Boolean.getBoolean(debug) || argumentDebugging);

        return new PromptResponseSimple(annotatedResponse, document.getMetaObjectResults(), document.getIntermediateResults());
    }

    private AnnotationResult<? extends RagMultiDocumentContext<?>> getAnnotations(final RagMultiDocumentContext<?> document, final float parsedMinSimilarity, final int parsedMinWords) {
        return document.annotateDocumentContext(
                parsedMinSimilarity,
                parsedMinWords,
                sentenceSplitter,
                similarityCalculator,
                sentenceVectorizer);
    }

    private String getLinks(final RagMultiDocumentContext<?> document) {
        if (document.getLinks().isEmpty()) {
            return "";
        }

        return System.lineSeparator() + System.lineSeparator() +
                "Links:" + System.lineSeparator() +
                document.individualContexts()
                        .stream()
                        .sorted(Comparator.comparing(RagDocumentContext::getGroup))
                        .filter(ragDoc -> StringUtils.isNotBlank(ragDoc.link()))
                        .map(ragDoc ->
                                getRagDocGroupPrefix(ragDoc)
                                        + ragDoc.link()
                                        + getRagDocKeywordsSuffix(ragDoc))
                        .filter(StringUtils::isNotBlank)
                        .map(link -> "* " + link)
                        .reduce("", (a, b) -> a + System.lineSeparator() + b);
    }

    private String getRagDocGroupPrefix(final RagDocumentContext<?> ragDoc) {
        return StringUtils.isBlank(ragDoc.group()) ? "" : ragDoc.group() + ": ";
    }

    private String getRagDocKeywordsSuffix(final RagDocumentContext<?> ragDoc) {
        return CollectionUtils.isEmpty(ragDoc.keywordMatches()) ? "" : " (Keywords: " + String.join(", ", ragDoc.keywordMatches()) + ")";
    }

    private String getDebugLinks(final RagMultiDocumentContext<?> document, final boolean argumentDebugging) {
        if (!argumentDebugging || StringUtils.isBlank(document.debug())) {
            return "";
        }

        return System.lineSeparator() + System.lineSeparator() +
                "Debug:" + System.lineSeparator() + document.debug();
    }

    private String getAnnotationCoverage(AnnotationResult<? extends RagMultiDocumentContext<?>> annotationResult, final boolean argumentDebugging) {
        if (!argumentDebugging || annotationResult == null) {
            return "";
        }

        return System.lineSeparator() + System.lineSeparator() +
                "Annotation Coverage:" + annotationResult.annotationCoverage();
    }

    /**
     * Captures all the output to display from a tool call
     *
     * @param annotationResult The result of annotating the response
     * @param links            Link to the source content
     * @param debug            Any debug information
     */
    private record PromptResponse(AnnotationResult<? extends RagMultiDocumentContext<?>> annotationResult, String links,
                                  String debug) {
    }
}
