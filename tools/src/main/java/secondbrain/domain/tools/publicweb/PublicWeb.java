package secondbrain.domain.tools.publicweb;

import com.google.common.collect.ImmutableList;
import io.vavr.API;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientBuilder;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.context.SentenceSplitter;
import secondbrain.domain.context.SentenceVectorizer;
import secondbrain.domain.exceptions.FailedTool;
import secondbrain.domain.prompt.PromptBuilderSelector;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.validate.ValidateString;
import secondbrain.infrastructure.ollama.OllamaClient;
import secondbrain.infrastructure.publicweb.PublicWebClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * A tool that downloads a public file from HTTP and uses it as the context for a query.
 */
@ApplicationScoped
public class PublicWeb implements Tool<Void> {

    private static final String INSTRUCTIONS = """
            You are a helpful assistant.
            You are given a question and the contents of a document related to the question.
            You must assume the information required to answer the question is present in the document.
            You must answer the question based on the document provided.
            You will be tipped $1000 for answering the question directly from the document.
            When the user asks a question indicating that they want to know about document, you must generate the answer based on the document.
            You will be penalized for answering that the document can not be accessed.
            """.stripLeading();

    @Inject
    @ConfigProperty(name = "sb.ollama.model", defaultValue = "llama3.2")
    private String model;

    @Inject
    @ConfigProperty(name = "sb.ollama.contextwindow")
    private Optional<String> contextWindow;

    @Inject
    private PublicWebClient publicWebClient;

    @Inject
    private ArgsAccessor argsAccessor;

    @Inject
    private SentenceSplitter sentenceSplitter;

    @Inject
    private SentenceVectorizer sentenceVectorizer;

    @Inject
    private PromptBuilderSelector promptBuilderSelector;

    @Inject
    private OllamaClient ollamaClient;

    @Inject
    private ValidateString validateString;

    @Override
    public String getName() {
        return PublicWeb.class.getSimpleName();
    }

    @Override
    public String getDescription() {
        return "Downloads a document from a supplied URL and queries it";
    }

    @Override
    public List<ToolArguments> getArguments() {
        return ImmutableList.of(new ToolArguments(
                "url",
                "The URL of the document to download",
                ""));
    }

    @Override
    public List<RagDocumentContext<Void>> getContext(
            final Map<String, String> context,
            final String prompt,
            final List<ToolArgs> arguments) {
        final Arguments parsedArgs = Arguments.fromToolArgs(arguments, context, argsAccessor, validateString, model, contextWindow);

        if (StringUtils.isBlank(parsedArgs.url())) {
            throw new FailedTool("You must provide a URL to download");
        }

        return Try.withResources(ClientBuilder::newClient)
                .of(client -> publicWebClient.getDocument(client, parsedArgs.url()))
                .map(this::getDocumentContext)
                .map(List::of)
                .get();
    }

    @Override
    public RagMultiDocumentContext<Void> call(
            final Map<String, String> context,
            final String prompt,
            final List<ToolArgs> arguments) {

        final List<RagDocumentContext<Void>> contextList = getContext(context, prompt, arguments);

        final Arguments parsedArgs = Arguments.fromToolArgs(
                arguments,
                context,
                argsAccessor,
                validateString,
                model,
                contextWindow);

        if (StringUtils.isBlank(parsedArgs.url())) {
            throw new FailedTool("You must provide a URL to download");
        }

        final Try<RagMultiDocumentContext<Void>> result = Try.of(() -> contextList)
                .map(ragDoc -> mergeContext(ragDoc, parsedArgs.model()))
                .map(ragContext -> ragContext.updateDocument(promptBuilderSelector
                        .getPromptBuilder(parsedArgs.model())
                        .buildFinalPrompt(
                                INSTRUCTIONS,
                                ragContext.getDocumentLeft(parsedArgs.contextWindowChars()),
                                prompt)))
                .map(ragDoc -> ollamaClient.callOllama(ragDoc, parsedArgs.model(), parsedArgs.contextWindow()));

        // Handle mapFailure in isolation to avoid intellij making a mess of the formatting
        // https://github.com/vavr-io/vavr/issues/2411
        return result.mapFailure(API.Case(API.$(), ex -> new FailedTool("Failed to call Ollama", ex)))
                .get();
    }

    private RagMultiDocumentContext<Void> mergeContext(final List<RagDocumentContext<Void>> context, final String customModel) {
        return new RagMultiDocumentContext<>(
                context.stream()
                        .map(ragDoc -> promptBuilderSelector
                                .getPromptBuilder(customModel)
                                .buildContextPrompt(
                                        "Downloaded Document",
                                        ragDoc.document()))
                        .collect(Collectors.joining("\n")),
                context);
    }

    private RagDocumentContext<Void> getDocumentContext(final String document) {
        return Try.of(() -> sentenceSplitter.splitDocument(document, 10))
                .map(sentences -> new RagDocumentContext<Void>(document,
                        sentences.stream()
                                .map(sentenceVectorizer::vectorize)
                                .collect(Collectors.toList())))
                .onFailure(throwable -> System.err.println("Failed to vectorize sentences: " + ExceptionUtils.getRootCauseMessage(throwable)))
                // If we can't vectorize the sentences, just return the document
                .recover(e -> new RagDocumentContext<>(document, List.of()))
                .get();
    }

    /**
     * A record that hold the arguments used by the tool. This centralizes the logic for extracting, validating, and sanitizing
     * the various inputs to the tool.
     */
    record Arguments(String url, String model, @Nullable Integer contextWindow, int contextWindowChars) {
        public static Arguments fromToolArgs(final List<ToolArgs> arguments, Map<String, String> context, ArgsAccessor argsAccessor, ValidateString validateString, String model, Optional<String> contextWindow) {
            final String url = argsAccessor.getArgument(arguments, "url", "");

            final String customModel = Try.of(() -> context.get("custom_model"))
                    .mapTry(validateString::throwIfEmpty)
                    .recover(e -> model)
                    .get();

            final Integer contextWindowValue = Try.of(contextWindow::get)
                    .map(Integer::parseInt)
                    .recover(e -> Constants.DEFAULT_CONTENT_WINDOW)
                    .get();

            final int contextWindowChars = contextWindowValue == null
                    ? Constants.DEFAULT_MAX_CONTEXT_LENGTH
                    : (int) (contextWindowValue * Constants.CONTENT_WINDOW_BUFFER * Constants.CHARACTERS_PER_TOKEN);

            return new Arguments(url, customModel, contextWindowValue, contextWindowChars);
        }
    }
}
