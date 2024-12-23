package secondbrain.domain.tools.uploadeddoc;

import com.google.common.collect.ImmutableList;
import io.vavr.API;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import secondbrain.domain.config.ModelConfig;
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

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A tool that downloads a public file from HTTP and uses it as the context for a query.
 */
@ApplicationScoped
public class UploadedDoc implements Tool<Void> {

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
    private ModelConfig modelConfig;

    @Inject
    private SentenceSplitter sentenceSplitter;

    @Inject
    private SentenceVectorizer sentenceVectorizer;

    @Inject
    private PromptBuilderSelector promptBuilderSelector;

    @Inject
    private ValidateString validateString;

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

    public List<RagDocumentContext<Void>> getContext(
            final Map<String, String> context,
            final String prompt,
            final List<ToolArgs> arguments) {
        final Arguments parsedArgs = Arguments.fromToolArgs(context, validateString);

        if (StringUtils.isBlank(parsedArgs.document())) {
            throw new FailedTool("No document found in context");
        }

        return List.of(getDocumentContext(parsedArgs.document()));
    }

    @Override
    public RagMultiDocumentContext<Void> call(
            final Map<String, String> context,
            final String prompt,
            final List<ToolArgs> arguments) {

        final Arguments parsedArgs = Arguments.fromToolArgs(context, validateString);

        final Try<RagMultiDocumentContext<Void>> result = Try.of(() -> getContext(context, prompt, arguments))
                .map(ragDoc -> mergeContext(ragDoc, modelConfig.getCalculatedModel(context)))
                .map(ragContext -> ragContext.updateDocument(promptBuilderSelector
                        .getPromptBuilder(modelConfig.getCalculatedModel(context))
                        .buildFinalPrompt(
                                INSTRUCTIONS,
                                ragContext.getDocumentLeft(modelConfig.getCalculatedContextWindowChars()),
                                prompt)))
                .map(ragDoc -> ollamaClient.callOllama(
                        ragDoc,
                        modelConfig.getCalculatedModel(context),
                        modelConfig.getCalculatedContextWindow()));

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
                                        "Uploaded Document",
                                        ragDoc.document()))
                        .collect(Collectors.joining("\n")),
                context);
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

    record Arguments(String document) {
        public static Arguments fromToolArgs(final Map<String, String> context, final ValidateString validateString) {
            final String uploadedDocument = Try.of(() -> context.get("document"))
                    .recover(throwable -> "")
                    .get();

            return new Arguments(uploadedDocument);
        }
    }
}
