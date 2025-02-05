package secondbrain.domain.tools.uploadeddoc;

import ai.djl.repository.FilenameUtils;
import io.vavr.API;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.args.Argument;
import secondbrain.domain.config.ModelConfig;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.context.SentenceSplitter;
import secondbrain.domain.context.SentenceVectorizer;
import secondbrain.domain.converter.FileToText;
import secondbrain.domain.exceptions.EmptyString;
import secondbrain.domain.exceptions.ExternalFailure;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.limit.DocumentTrimmer;
import secondbrain.domain.prompt.PromptBuilderSelector;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.validate.ValidateString;
import secondbrain.infrastructure.ollama.OllamaClient;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.google.common.base.Predicates.instanceOf;

/**
 * A tool that downloads a public file from HTTP and uses it as the context for a query.
 */
@ApplicationScoped
public class UploadedDoc implements Tool<Void> {
    public static final String UPLOADED_DOC_KEYWORD_ARG = "keywords";
    public static final String UPLOADED_DOC_KEYWORD_WINDOW_ARG = "keywordWindow";

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
    private OllamaClient ollamaClient;

    @Inject
    private Arguments parsedArgs;

    @Inject
    private FileToText fileToText;

    @Inject
    private DocumentTrimmer documentTrimmer;

    @Inject
    private ValidateString validateString;

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
        return List.of(
                new ToolArguments(UPLOADED_DOC_KEYWORD_ARG, "An optional list of keywords used to trim the document", ""),
                new ToolArguments(UPLOADED_DOC_KEYWORD_WINDOW_ARG, "The window size around any matching keywords", "")
        );
    }

    @Override
    public String getContextLabel() {
        return "File Contents";
    }

    @Override
    public List<RagDocumentContext<Void>> getContext(
            final Map<String, String> context,
            final String prompt,
            final List<ToolArgs> arguments) {
        parsedArgs.setInputs(arguments, prompt, context);

        if (parsedArgs.getDocument().length == 0) {
            throw new InternalFailure("No document found in context");
        }

        return List.of(getDocumentContext(parsedArgs.getDocument()));
    }

    @Override
    public RagMultiDocumentContext<Void> call(
            final Map<String, String> context,
            final String prompt,
            final List<ToolArgs> arguments) {

        final Try<RagMultiDocumentContext<Void>> result = Try.of(() -> getContext(context, prompt, arguments))
                .map(ragDoc -> mergeContext(ragDoc, modelConfig.getCalculatedModel(context)))
                .map(ragContext -> ragContext.updateDocument(promptBuilderSelector
                        .getPromptBuilder(modelConfig.getCalculatedModel(context))
                        .buildFinalPrompt(
                                INSTRUCTIONS,
                                ragContext.getDocumentLeft(modelConfig.getCalculatedContextWindowChars()),
                                prompt)))
                .map(ragDoc -> ollamaClient.callOllamaWithCache(
                        ragDoc,
                        modelConfig.getCalculatedModel(context),
                        getName(),
                        modelConfig.getCalculatedContextWindow()));

        // Handle mapFailure in isolation to avoid intellij making a mess of the formatting
        // https://github.com/vavr-io/vavr/issues/2411
        return result.mapFailure(
                        API.Case(API.$(instanceOf(EmptyString.class)), throwable -> new InternalFailure("The document was empty")),
                        API.Case(API.$(instanceOf(InternalFailure.class)), throwable -> throwable),
                        API.Case(API.$(), ex -> new ExternalFailure(getName() + " failed to call Ollama", ex)))
                .get();
    }

    private RagMultiDocumentContext<Void> mergeContext(final List<RagDocumentContext<Void>> context, final String customModel) {
        return new RagMultiDocumentContext<>(
                context.stream()
                        .map(ragDoc -> promptBuilderSelector
                                .getPromptBuilder(customModel)
                                .buildContextPrompt(
                                        getContextLabel(),
                                        ragDoc.document()))
                        .collect(Collectors.joining("\n")),
                context);
    }

    private RagDocumentContext<Void> getDocumentContext(final byte[] document) {
        final File tempFile = createTempFile();

        Try.withResources(() -> new FileOutputStream(tempFile))
                .of(writer -> Try.run(() -> writer.write(document)));

        final String contents = fileToText.convert(tempFile.getAbsolutePath());

        return Try.of(() -> documentTrimmer.trimDocument(
                        contents,
                        parsedArgs.getKeywords(),
                        parsedArgs.getKeywordWindow()))
                .map(validateString::throwIfEmpty)
                .map(this::getTrimmedDocumentContext)
                .onFailure(throwable -> System.err.println("Failed to vectorize sentences: " + ExceptionUtils.getRootCauseMessage(throwable)))
                // If we can't vectorize the sentences, just return the document
                .recover(e -> new RagDocumentContext<>(getContextLabel(), contents, List.of()))
                .get();
    }

    private RagDocumentContext<Void> getTrimmedDocumentContext(final String document) {
        return Try.of(() -> sentenceSplitter.splitDocument(document, 10))
                .map(sentences -> new RagDocumentContext<Void>(
                        getContextLabel(),
                        document,
                        sentences.stream()
                                .map(sentenceVectorizer::vectorize)
                                .collect(Collectors.toList())))
                .onFailure(throwable -> System.err.println("Failed to vectorize sentences: " + ExceptionUtils.getRootCauseMessage(throwable)))
                // If we can't vectorize the sentences, just return the document
                .recover(e -> new RagDocumentContext<>(getContextLabel(), document, List.of()))
                .get();
    }

    private File createTempFile() {
        return Try.of(() -> File.createTempFile("tempFile", "." + FilenameUtils.getFileExtension(parsedArgs.getFileName())))
                .peek(File::deleteOnExit)
                .get();
    }
}

@ApplicationScoped
class Arguments {
    private List<ToolArgs> arguments;

    private String prompt;

    private Map<String, String> context;

    @Inject
    @ConfigProperty(name = "sb.upload.keywords")
    private Optional<String> uploadKeywords;

    @Inject
    @ConfigProperty(name = "sb.upload.keywordwindow")
    private Optional<String> keywordWindow;

    @Inject
    private ArgsAccessor argsAccessor;

    public void setInputs(final List<ToolArgs> arguments, final String prompt, final Map<String, String> context) {
        this.arguments = arguments;
        this.prompt = prompt;
        this.context = context;
    }

    public byte[] getDocument() {
        return Try.of(() -> context.get("document"))
                .map(document -> Base64.getDecoder().decode(document))
                .recover(throwable -> new byte[0])
                .get();
    }

    public String getFileName() {
        return Try.of(() -> context.get("filename"))
                .recover(throwable -> "")
                .get();
    }

    public List<String> getKeywords() {
        return argsAccessor.getArgumentList(
                        uploadKeywords::get,
                        arguments,
                        context,
                        UploadedDoc.UPLOADED_DOC_KEYWORD_ARG,
                        "upload_keywords",
                        "")
                .stream()
                .map(Argument::value)
                .toList();
    }

    public int getKeywordWindow() {
        final Argument argument = argsAccessor.getArgument(
                keywordWindow::get,
                arguments,
                context,
                UploadedDoc.UPLOADED_DOC_KEYWORD_WINDOW_ARG,
                "upload_keyword_window",
                Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH + "");

        return NumberUtils.toInt(argument.value(), Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH);
    }
}
