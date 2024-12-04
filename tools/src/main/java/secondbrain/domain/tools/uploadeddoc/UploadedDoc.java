package secondbrain.domain.tools.uploadeddoc;

import com.google.common.collect.ImmutableList;
import io.vavr.control.Try;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientBuilder;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.SentenceSplitter;
import secondbrain.domain.context.SentenceVectorizer;
import secondbrain.domain.context.SimilarityCalculator;
import secondbrain.domain.debug.DebugToolArgs;
import secondbrain.domain.prompt.PromptBuilderSelector;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.infrastructure.ollama.OllamaClient;
import secondbrain.infrastructure.ollama.OllamaGenerateBody;
import secondbrain.infrastructure.publicweb.PublicWebClient;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A tool that downloads a public file from HTTP and uses it as the context for a query.
 */
@Dependent
public class UploadedDoc implements Tool {

    private static final String INSTRUCTIONS = """
            You are a helpful assistant.
            You are given a question and the contents of a document related to the question.
            You must assume the information required to answer the question is present in the document.
            You must answer the question based on the document provided.
            You will be tipped $1000 for answering the question directly from the document.
            When the user asks a question indicating that they want to know about document, you must generate the answer based on the document.
            """.stripLeading();

    @Inject
    @ConfigProperty(name = "sb.ollama.model", defaultValue = "llama3.2")
    String model;

    @Inject
    @ConfigProperty(name = "sb.ollama.contentlength", defaultValue = "" + Constants.MAX_CONTEXT_LENGTH)
    String limit;

    @Inject
    @ConfigProperty(name = "sb.annotation.minsimilarity", defaultValue = "0.5")
    String minSimilarity;

    @Inject
    private PublicWebClient publicWebClient;

    @Inject
    private ArgsAccessor argsAccessor;

    @Inject
    private SentenceSplitter sentenceSplitter;

    @Inject
    private SimilarityCalculator similarityCalculator;

    @Inject
    private SentenceVectorizer sentenceVectorizer;

    @Inject
    private PromptBuilderSelector promptBuilderSelector;

    @Inject
    private OllamaClient ollamaClient;

    @Inject
    private DebugToolArgs debugToolArgs;

    @Override
    public String getName() {
        return UploadedDoc.class.getSimpleName();
    }

    @Override
    public String getDescription() {
        return "Queries an uploaded document";
    }

    @Override
    public List<ToolArguments> getArguments() {
        return ImmutableList.of();
    }

    @Override
    public String call(
            final Map<String, String> context,
            final String prompt,
            final List<ToolArgs> arguments) {

        final String uploadedDocument = Try.of(() -> context.get("document"))
                .recover(throwable -> "")
                .get();

        if (StringUtils.isBlank(uploadedDocument)) {
            return "No document found in context";
        }

        final float parsedMinSimilarity = Try.of(() -> Float.parseFloat(minSimilarity))
                .recover(throwable -> 0.5f)
                .get();

        return Try.of(() -> uploadedDocument)
                .map(this::getDocumentContext)
                .map(doc -> doc.updateDocument(promptBuilderSelector
                        .getPromptBuilder(model)
                        .buildContextPrompt("Document", doc.getDocumentLeft(NumberUtils.toInt(limit, Constants.MAX_CONTEXT_LENGTH)))))
                .map(ragContext -> ragContext.updateDocument(promptBuilderSelector
                        .getPromptBuilder(model)
                        .buildFinalPrompt(
                                INSTRUCTIONS,
                                ragContext.getDocumentLeft(NumberUtils.toInt(limit, Constants.MAX_CONTEXT_LENGTH)),
                                prompt)))
                .map(this::callOllama)
                .map(result -> result.annotateDocumentContext(parsedMinSimilarity, 10, sentenceSplitter, similarityCalculator, sentenceVectorizer))
                .map(response -> response
                        + debugToolArgs.debugArgs(arguments, true))
                .recover(throwable -> "Failed to get document: " + throwable.getMessage())
                .get();
    }

    private RagDocumentContext<Void> getDocumentContext(final String document) {
        return Try.of(() -> sentenceSplitter.splitDocument(document, 10))
                .map(sentences -> new RagDocumentContext<Void>(document, sentences.stream()
                        .map(sentenceVectorizer::vectorize)
                        .collect(Collectors.toList())))
                .onFailure(throwable -> System.err.println("Failed to vectorize sentences: " + ExceptionUtils.getRootCauseMessage(throwable)))
                // If we can't vectorize the sentences, just return the document
                .recover(e -> new RagDocumentContext<>(document, List.of()))
                .get();
    }

    private RagDocumentContext<Void> callOllama(final RagDocumentContext<Void> llmPrompt) {
        return Try.withResources(ClientBuilder::newClient)
                .of(client -> ollamaClient.getTools(
                        client,
                        new OllamaGenerateBody(model, llmPrompt.document(), false)))
                .map(response -> new RagDocumentContext<Void>(response.response(), llmPrompt.sentences()))
                .get();
    }
}
