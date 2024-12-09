package secondbrain.domain.tools.uploadeddoc;

import com.google.common.collect.ImmutableList;
import io.vavr.API;
import io.vavr.control.Try;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientBuilder;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
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
import secondbrain.infrastructure.ollama.OllamaClient;
import secondbrain.infrastructure.ollama.OllamaGenerateBodyWithContext;

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
            The supplied document is the uploaded document.
            You must assume the information required to answer the question is present in the document.
            You must answer the question based on the document provided.
            You will be tipped $1000 for answering the question directly from the document.
            When the user asks a question indicating that they want to know about the uploaded document, you must generate the answer based on the supplied document.
            You will be penalized for answering that the document was not uploaded.
            """.stripLeading();

    @Inject
    @ConfigProperty(name = "sb.ollama.model", defaultValue = "llama3.2")
    String model;

    @Inject
    @ConfigProperty(name = "sb.ollama.contentlength", defaultValue = "" + Constants.MAX_CONTEXT_LENGTH)
    String limit;

    @Inject
    private SentenceSplitter sentenceSplitter;

    @Inject
    private SentenceVectorizer sentenceVectorizer;

    @Inject
    private PromptBuilderSelector promptBuilderSelector;

    @Inject
    private OllamaClient ollamaClient;

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
    public RagMultiDocumentContext<?> call(
            final Map<String, String> context,
            final String prompt,
            final List<ToolArgs> arguments) {

        final String uploadedDocument = Try.of(() -> context.get("document"))
                .recover(throwable -> "")
                .get();

        if (StringUtils.isBlank(uploadedDocument)) {
            throw new FailedTool("No document found in context");
        }

        final Try<RagMultiDocumentContext<Void>> result = Try.of(() -> uploadedDocument)
                .map(this::getDocumentContext)
                .map(ragDoc -> new RagMultiDocumentContext<>(ragDoc.document(), List.of(ragDoc)))
                .map(doc -> doc.updateDocument(promptBuilderSelector
                        .getPromptBuilder(model)
                        .buildContextPrompt("Uploaded Document", doc.getDocumentLeft(NumberUtils.toInt(limit, Constants.MAX_CONTEXT_LENGTH)))))
                .map(ragContext -> ragContext.updateDocument(promptBuilderSelector
                        .getPromptBuilder(model)
                        .buildFinalPrompt(
                                INSTRUCTIONS,
                                ragContext.getDocumentLeft(NumberUtils.toInt(limit, Constants.MAX_CONTEXT_LENGTH)),
                                prompt)))
                .map(this::callOllama);

        // Handle mapFailure in isolation to avoid intellij making a mess of the formatting
        // https://github.com/vavr-io/vavr/issues/2411
        return result.mapFailure(API.Case(API.$(), ex -> new FailedTool("Failed to call Ollama", ex)))
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

    private RagMultiDocumentContext<Void> callOllama(final RagMultiDocumentContext<Void> ragDoc) {
        return Try.withResources(ClientBuilder::newClient)
                .of(client -> ollamaClient.getTools(
                        client,
                        new OllamaGenerateBodyWithContext<>(model, ragDoc, false)))
                .get();
    }
}
