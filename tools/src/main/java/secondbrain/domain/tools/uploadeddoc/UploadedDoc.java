package secondbrain.domain.tools.uploadeddoc;

import ai.djl.repository.FilenameUtils;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jooq.lambda.Seq;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.args.Argument;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.context.SentenceSplitter;
import secondbrain.domain.context.SentenceVectorizer;
import secondbrain.domain.converter.FileToText;
import secondbrain.domain.exceptionhandling.ExceptionMapping;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.hooks.HooksContainer;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.limit.DocumentTrimmer;
import secondbrain.domain.limit.TrimResult;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.validate.ValidateString;
import secondbrain.infrastructure.llm.LlmClient;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A tool that downloads a public file from HTTP and uses it as the context for a query.
 */
@ApplicationScoped
public class UploadedDoc implements Tool<Void> {
    public static final String UPLOADED_DOC_KEYWORD_ARG = "keywords";
    public static final String UPLOADED_DOC_KEYWORD_WINDOW_ARG = "keywordWindow";
    public static final String UPLOADED_DOC_ENTITY_NAME_CONTEXT_ARG = "entityName";
    public static final String PREPROCESSOR_HOOKS_CONTEXT_ARG = "preProcessorHooks";
    public static final String PREINITIALIZATION_HOOKS_CONTEXT_ARG = "preInitializationHooks";
    public static final String POSTINFERENCE_HOOKS_CONTEXT_ARG = "postInferenceHooks";

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
    private SentenceSplitter sentenceSplitter;

    @Inject
    private SentenceVectorizer sentenceVectorizer;

    @Inject
    @Preferred
    private LlmClient llmClient;

    @Inject
    private UploadDocConfig config;

    @Inject
    private FileToText fileToText;

    @Inject
    private DocumentTrimmer documentTrimmer;

    @Inject
    private ValidateString validateString;

    @Inject
    private ExceptionMapping exceptionMapping;

    @Inject
    private HooksContainer hooksContainer;

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
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {
        final UploadDocConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        if (parsedArgs.getDocument().length == 0) {
            throw new InternalFailure("No document found in context");
        }

        // Get preinitialization hooks before ragdocs
        final List<RagDocumentContext<Void>> preinitHooks = Seq.seq(hooksContainer.getMatchingPreProcessorHooks(parsedArgs.getPreinitializationHooks()))
                .foldLeft(List.of(), (docs, hook) -> hook.process(getName(), docs));

        final List<RagDocumentContext<Void>> ragDocs = List.of(getDocumentContext(parsedArgs));

        // Combine preinitialization hooks with ragDocs
        final List<RagDocumentContext<Void>> combinedDocs = Stream.concat(preinitHooks.stream(), ragDocs.stream()).toList();

        // Apply preprocessing hooks
        return Seq.seq(hooksContainer.getMatchingPreProcessorHooks(parsedArgs.getPreprocessingHooks()))
                .foldLeft(combinedDocs, (docs, hook) -> hook.process(getName(), docs));
    }

    @Override
    public RagMultiDocumentContext<Void> call(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {

        final UploadDocConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        final Try<RagMultiDocumentContext<Void>> result = Try.of(() -> getContext(environmentSettings, prompt, arguments))
                .map(ragDoc -> new RagMultiDocumentContext<>(prompt, INSTRUCTIONS, ragDoc))
                .map(ragDoc -> llmClient.callWithCache(
                        ragDoc,
                        environmentSettings,
                        getName()));

        final RagMultiDocumentContext<Void> mappedResult = exceptionMapping.map(result).get();

        // Apply postinference hooks
        return Seq.seq(hooksContainer.getMatchingPostInferenceHooks(parsedArgs.getPostInferenceHooks()))
                .foldLeft(mappedResult, (docs, hook) -> hook.process(getName(), docs));
    }

    private RagDocumentContext<Void> getDocumentContext(final UploadDocConfig.LocalArguments parsedArgs) {
        final File tempFile = createTempFile(parsedArgs);

        Try.withResources(() -> new FileOutputStream(tempFile))
                .of(writer -> Try.run(() -> writer.write(parsedArgs.getDocument())));

        final String contents = fileToText.convert(tempFile.getAbsolutePath());

        return Try.of(() -> documentTrimmer.trimDocumentToKeywords(
                        contents,
                        parsedArgs.getKeywords(),
                        parsedArgs.getKeywordWindow()))
                .map(trimDocument -> validateString.throwIfBlank(trimDocument, TrimResult::document))
                .map(trimmedResult -> getTrimmedDocumentContext(trimmedResult, parsedArgs))
                .onFailure(throwable -> System.err.println("Failed to vectorize sentences: " + ExceptionUtils.getRootCauseMessage(throwable)))
                .get();
    }

    private RagDocumentContext<Void> getTrimmedDocumentContext(final TrimResult trimResult, final UploadDocConfig.LocalArguments parsedArgs) {
        return Try.of(() -> sentenceSplitter.splitDocument(trimResult.document(), 10))
                .map(sentences -> new RagDocumentContext<Void>(
                        getName(),
                        getContextLabel(),
                        trimResult.document(),
                        sentenceVectorizer.vectorize(sentences, parsedArgs.getEntity()),
                        null,
                        null,
                        null,
                        trimResult.keywordMatches()))
                .onFailure(throwable -> System.err.println("Failed to vectorize sentences: " + ExceptionUtils.getRootCauseMessage(throwable)))
                .get();
    }

    private File createTempFile(final UploadDocConfig.LocalArguments parsedArgs) {
        return Try.of(() -> File.createTempFile("tempFile", "." + FilenameUtils.getFileExtension(parsedArgs.getFileName())))
                .peek(File::deleteOnExit)
                .get();
    }
}

@ApplicationScoped
class UploadDocConfig {
    @Inject
    @ConfigProperty(name = "sb.upload.keywords")
    private Optional<String> configUploadKeywords;

    @Inject
    @ConfigProperty(name = "sb.upload.keywordwindow")
    private Optional<String> configKeywordWindow;

    @Inject
    @ConfigProperty(name = "sb.upload.preprocessorHooks", defaultValue = "")
    private Optional<String> configPreprocessorHooks;

    @Inject
    @ConfigProperty(name = "sb.upload.preinitializationHooks", defaultValue = "")
    private Optional<String> configPreinitializationHooks;

    @Inject
    @ConfigProperty(name = "sb.upload.postinferenceHooks", defaultValue = "")
    private Optional<String> configPostInferenceHooks;

    @Inject
    private ArgsAccessor argsAccessor;

    public Optional<String> getConfigUploadKeywords() {
        return configUploadKeywords;
    }

    public Optional<String> getConfigKeywordWindow() {
        return configKeywordWindow;
    }

    public Optional<String> getConfigPreprocessorHooks() {
        return configPreprocessorHooks;
    }

    public Optional<String> getConfigPreinitializationHooks() {
        return configPreinitializationHooks;
    }

    public Optional<String> getConfigPostInferenceHooks() {
        return configPostInferenceHooks;
    }

    public ArgsAccessor getArgsAccessor() {
        return argsAccessor;
    }

    public class LocalArguments {
        private final List<ToolArgs> arguments;

        private final String prompt;

        private final Map<String, String> context;

        public LocalArguments(final List<ToolArgs> arguments, final String prompt, final Map<String, String> context) {
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
            return getArgsAccessor().getArgumentList(
                            getConfigUploadKeywords()::get,
                            arguments,
                            context,
                            UploadedDoc.UPLOADED_DOC_KEYWORD_ARG,
                            UploadedDoc.UPLOADED_DOC_KEYWORD_ARG,
                            "")
                    .stream()
                    .map(Argument::value)
                    .toList();
        }

        public int getKeywordWindow() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigKeywordWindow()::get,
                    arguments,
                    context,
                    UploadedDoc.UPLOADED_DOC_KEYWORD_WINDOW_ARG,
                    UploadedDoc.UPLOADED_DOC_KEYWORD_WINDOW_ARG,
                    Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH + "");

            return NumberUtils.toInt(argument.value(), Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH);
        }

        public String getEntity() {
            return getArgsAccessor().getArgument(
                    null,
                    null,
                    context,
                    null,
                    UploadedDoc.UPLOADED_DOC_ENTITY_NAME_CONTEXT_ARG,
                    "").value();
        }

        public String getPreprocessingHooks() {
            return getArgsAccessor().getArgument(
                    getConfigPreprocessorHooks()::get,
                    arguments,
                    context,
                    UploadedDoc.PREPROCESSOR_HOOKS_CONTEXT_ARG,
                    UploadedDoc.PREPROCESSOR_HOOKS_CONTEXT_ARG,
                    "").value();
        }

        public String getPreinitializationHooks() {
            return getArgsAccessor().getArgument(
                    getConfigPreinitializationHooks()::get,
                    arguments,
                    context,
                    UploadedDoc.PREINITIALIZATION_HOOKS_CONTEXT_ARG,
                    UploadedDoc.PREINITIALIZATION_HOOKS_CONTEXT_ARG,
                    "").value();
        }

        public String getPostInferenceHooks() {
            return getArgsAccessor().getArgument(
                    getConfigPostInferenceHooks()::get,
                    arguments,
                    context,
                    UploadedDoc.POSTINFERENCE_HOOKS_CONTEXT_ARG,
                    UploadedDoc.POSTINFERENCE_HOOKS_CONTEXT_ARG,
                    "").value();
        }
    }
}
