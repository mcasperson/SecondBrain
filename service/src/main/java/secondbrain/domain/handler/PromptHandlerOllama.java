package secondbrain.domain.handler;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ProcessingException;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.context.*;
import secondbrain.domain.exceptionhandling.ExceptionHandler;
import secondbrain.domain.exceptions.FailedTool;
import secondbrain.domain.exceptions.InsufficientContext;
import secondbrain.domain.toolbuilder.ToolSelector;
import secondbrain.domain.tooldefs.ToolCall;

import java.util.Map;
import java.util.Objects;
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


    public String handlePrompt(final Map<String, String> context, final String prompt) {

        return handlePromptWithRetry(context, prompt, 1);
    }

    /**
     * Unfortunately the LLMs available to be run locally on most consumer hardware, typically around 8B params
     * quantized to 4 bits, can struggle to generate valid JSON in response to a request to select a tool.
     * So we retry a bunch of times to try and get a valid response.
     */
    public String handlePromptWithRetry(final Map<String, String> context, final String prompt, int count) {
        return Try.of(() -> toolSelector.getTool(prompt, context))
                .map(toolCall -> callTool(toolCall, context, prompt))
                .recover(ProcessingException.class, e -> "Failed to connect to Ollama. You must install Ollama from https://ollama.com/download: " + e.toString())
                .recover(InsufficientContext.class, Throwable::getMessage)
                .recoverWith(error -> Try.of(() -> {
                    // Selecting the wrong tool can manifest itself as an exception
                    if (count < TOOL_RETRY) {
                        return handlePromptWithRetry(context, prompt, count + 1);
                    }
                    throw error;
                }))
                .recover(Throwable.class, e -> "Failed to find a tool or call it: " + ExceptionUtils.getRootCauseMessage(e))
                .get();
    }


    private String callTool(@Nullable final ToolCall toolCall, final Map<String, String> context, final String prompt) {
        if (toolCall == null) {
            return "No tool found";
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
                .map(document -> new PromptResponse(
                        document.annotateDocumentContext(
                                parsedMinSimilarity,
                                parsedMinWords,
                                sentenceSplitter,
                                similarityCalculator,
                                sentenceVectorizer),
                        getLinks(document),
                        getDebugLinks(document, Boolean.getBoolean(debug) || argumentDebugging)))
                .map(response ->
                        response.annotationResult().result()
                                + response.links()
                                + response.debug()
                                + getAnnotationCoverage(response.annotationResult(), Boolean.getBoolean(debug) || argumentDebugging))
                .recover(FailedTool.class, exceptionHandler::getExceptionMessage)
                .get();
    }

    private String getLinks(final RagMultiDocumentContext<?> document) {
        if (document.getLinks().isEmpty()) {
            return "";
        }

        return System.lineSeparator() + System.lineSeparator() +
                "Links:" + System.lineSeparator() +
                document.getLinks()
                        .stream()
                        .filter(Objects::nonNull)
                        .map(link -> "* " + link)
                        .reduce("", (a, b) -> a + System.lineSeparator() + b);
    }

    private String getDebugLinks(final RagMultiDocumentContext<?> document, final boolean argumentDebugging) {
        if (!argumentDebugging || StringUtils.isBlank(document.debug())) {
            return "";
        }

        return System.lineSeparator() + System.lineSeparator() +
                "Debug:" + System.lineSeparator() + document.debug();
    }

    private String getAnnotationCoverage(AnnotationResult<? extends RagMultiDocumentContext<?>> annotationResult, final boolean argumentDebugging) {
        if (!argumentDebugging) {
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
