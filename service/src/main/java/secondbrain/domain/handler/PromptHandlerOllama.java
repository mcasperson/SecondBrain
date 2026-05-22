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

import java.util.List;
import java.util.Map;
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
    private Boolean debug;

    @Inject
    @ConfigProperty(name = "sb.output.disableAnnotations", defaultValue = "false")
    private Boolean disableAnnotations;

    @Inject
    @ConfigProperty(name = "sb.output.disableLinks", defaultValue = "false")
    private Boolean disableLinks;

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

    @Override
    public PromptHandlerResponse handlePrompt(final Map<String, String> context, final List<String> prompts) {
        return handlePromptWithRetry(context, prompts, 1);
    }

    /**
     * Unfortunately the LLMs available to be run locally on most consumer hardware, typically around 8B params
     * quantized to 4 bits, can struggle to generate valid JSON in response to a request to select a tool.
     * So we retry a bunch of times to try and get a valid response.
     */
    public PromptHandlerResponse handlePromptWithRetry(final Map<String, String> context, final List<String> prompts, final int count) {
        // Use the first prompt to select the tool
        final String firstPrompt = prompts.isEmpty() ? "" : prompts.get(0);
        return Try.of(() -> toolSelector.getTool(firstPrompt, context))
                .map(toolCall -> callTool(toolCall, context, prompts))
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
                        return handlePromptWithRetry(context, prompts, count + 1);
                    }
                    throw error;
                }))
                /*
                    The retry count is exhausted, so we return the error message to the user.
                 */
                .recover(Throwable.class, ex -> new PromptResponseSimple(exceptionHandler.getExceptionMessage(ex)))
                .get();
    }


    private PromptHandlerResponse callTool(@Nullable final ToolCall toolCall, final Map<String, String> context, final List<String> prompts) {
        if (toolCall == null) {
            return new PromptResponseSimple("No tool found");
        }

        logger.fine("Calling tool " + toolCall.tool().getName());

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

        logger.fine("Using minSimilarity: " + parsedMinSimilarity
                + ", minWords: " + parsedMinWords
                + ", argumentDebugging: " + argumentDebugging);

        /*
            The tool responds with a single RagMultiDocumentContext that contains a list of responses
            (one per prompt) and the context that was used to build the prompts.

            We use this information to generate a standardized output that includes the text response from the LLM,
            links to the context items, and annotations that link the LLM output to the original context.
         */
        return Try.of(() -> toolCall.call(context, prompts, logger))
                .map(document -> {
                    var accumulated = document.updateResponse("").appendResponses(document.getResponses());
                    return generateAnnotatedResponse(
                            accumulated,
                            parsedMinSimilarity,
                            parsedMinWords);
                })
                .recover(InternalFailure.class, ex -> new PromptResponseSimple(exceptionHandler.getExceptionMessage(ex)))
                .get();
    }

    private PromptHandlerResponse generateAnnotatedResponse(final RagMultiDocumentContext<?> document, final float parsedMinSimilarity, final int parsedMinWords) {
        if (disableAnnotations) {
            return new PromptResponseSimple(
                    document.getResponse(),
                    "",
                    disableLinks ? "" : getLinks(document),
                    debug ? getDebugLinks(document) : "",
                    document.generateMetaObjectResults(),
                    document.generateIntermediateResults());
        }

        final AnnotationResult<? extends RagMultiDocumentContext<?>> result = document.annotateDocumentContext(
                parsedMinSimilarity,
                parsedMinWords,
                sentenceSplitter,
                similarityCalculator,
                sentenceVectorizer);

        return new PromptResponseSimple(
                result.annotatedContent(),
                result.annotations(),
                disableLinks ? "" : getLinks(document),
                debug ? getDebugLinks(document) : "",
                document.generateMetaObjectResults(),
                document.generateIntermediateResults());
    }

    private String getLinks(final RagMultiDocumentContext<?> document) {
        if (document.generateLinks().isEmpty()) {
            return "";
        }

        return System.lineSeparator() + System.lineSeparator() +
                document.getIndividualContexts()
                        .stream()
                        .filter(ragDoc -> StringUtils.isNotBlank(ragDoc.link()))
                        .map(ragDoc -> ragDoc.link() + getRagDocKeywordsSuffix(ragDoc))
                        .filter(StringUtils::isNotBlank)
                        .map(link -> "* " + link)
                        .reduce("", (a, b) -> a + System.lineSeparator() + b);
    }

    private String getRagDocKeywordsSuffix(final RagDocumentContext<?> ragDoc) {
        return CollectionUtils.isEmpty(ragDoc.keywordMatches()) ? "" : " (Keywords: " + String.join(", ", ragDoc.keywordMatches()) + ")";
    }

    private String getDebugLinks(final RagMultiDocumentContext<?> document) {
        if (StringUtils.isBlank(document.debug())) {
            return "";
        }

        return System.lineSeparator() + System.lineSeparator() +
                "Debug:" + System.lineSeparator() + document.debug();
    }
}
