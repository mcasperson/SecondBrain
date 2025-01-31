package secondbrain.domain.tools.directoryscan;

import io.vavr.API;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientBuilder;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.config.ModelConfig;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.context.SentenceSplitter;
import secondbrain.domain.context.SentenceVectorizer;
import secondbrain.domain.converter.FileToText;
import secondbrain.domain.debug.DebugToolArgs;
import secondbrain.domain.exceptions.EmptyString;
import secondbrain.domain.exceptions.FailedTool;
import secondbrain.domain.limit.DocumentTrimmer;
import secondbrain.domain.limit.ListLimiter;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.prompt.PromptBuilderSelector;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.validate.ValidateString;
import secondbrain.infrastructure.ollama.OllamaClient;
import secondbrain.infrastructure.ollama.OllamaGenerateBody;
import secondbrain.infrastructure.ollama.OllamaGenerateBodyOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.google.common.base.Predicates.instanceOf;

/**
 * Scans the files in a directory and answers questions about them. This is useful when you have a bunch of reports
 * or other files that contain information that you want to query.
 */
@ApplicationScoped
public class DirectoryScan implements Tool<Void> {
    public static final String DIRECTORYSCAN_DISABLELINKS_ARG = "disableLinks";
    public static final String DIRECTORYSCAN_SUMMARIZE_INDIVIDUAL_FILES_ARG = "summarizeIndividualFiles";
    public static final String DIRECTORYSCAN_SUMMARIZE_KEYWORD_WINDOW = "keywordWindow";
    public static final String DIRECTORYSCAN_SUMMARIZE_KEYWORDS = "keywords";
    public static final String DIRECTORYSCAN_EXCLUDE_FILES = "excludeFiles";
    public static final String DIRECTORYSCAN_FILE_CONTENT_WINDOW = "fileContextWindow";
    public static final String DIRECTORYSCAN_INDIVIDUAL_DOCUMENT_PROMPT = "individualDocumentPrompt";
    public static final String DIRECTORYSCAN_FILE_CUSTOM_MODEL = "fileCustomModel";
    public static final String DIRECTORYSCAN_MAX_FILES = "maxfiles";
    public static final String DIRECTORYSCAN_DIRECTORY = "directory";

    private static final String INSTRUCTIONS = """
            You are given a question and the answer to the question from many individual files.
            You must assume the information required to answer the question is present in the individual file answers.
            You must answer the question based on the individual file answers provided.
            You must consider every individual file answers when providing the answer.
            When the user asks a question indicating that they want to know the changes in the repository, you must generate the answer based on the individual file answers.
            You will be penalized for suggesting manual steps to generate the answer.
            You will be penalized for responding that you don't have access to real-time data or directories.
            If there are no individual file answers, you must indicate that in the answer.
            """;
    private static final String FILE_INSTRUCTIONS = """
            You are given a question and the contents of a file.
            You must ignore any mention of a directory.
            You must assume the information required to answer the question is present in the file contents.
            You must answer the question based on the file contents provided.
            You must consider every file contents when providing the answer.
            When the user asks a question indicating that they want to know the changes in the repository, you must generate the answer based on the file contents.
            You will be penalized for suggesting manual steps to generate the answer.
            You will be penalized for responding that you don't have access to real-time data or directories.
            If the file content is empty, you must indicate that in the answer.
            """;
    @Inject
    LocalStorage localStorage;
    @Inject
    private ModelConfig modelConfig;
    @Inject
    private Arguments parsedArgs;
    @Inject
    private DebugToolArgs debugToolArgs;
    @Inject
    private OllamaClient ollamaClient;
    @Inject
    private ListLimiter listLimiter;
    @Inject
    private PromptBuilderSelector promptBuilderSelector;
    @Inject
    private ValidateString validateString;
    @Inject
    private SentenceSplitter sentenceSplitter;
    @Inject
    private SentenceVectorizer sentenceVectorizer;
    @Inject
    private FileToText fileToText;
    @Inject
    private Logger logger;
    @Inject
    private DocumentTrimmer documentTrimmer;

    @Override
    public String getName() {
        return DirectoryScan.class.getSimpleName();
    }

    @Override
    public String getDescription() {
        return """
                Scans the files in a directory and answers questions about them.
                Example prompts include:
                * Given the files in the directory "/tmp/reports", find references to the company "Acme".
                """;
    }

    @Override
    public String getContextLabel() {
        return "File Contents";
    }

    @Override
    public List<ToolArguments> getArguments() {
        return List.of(
                new ToolArguments("directory", "The directory containing the files to scan", ""),
                new ToolArguments("maxfiles", "The maximum number of files to scan", "-1")
        );
    }

    @Override
    public List<RagDocumentContext<Void>> getContext(
            final Map<String, String> context,
            final String prompt,
            final List<ToolArgs> arguments) {

        parsedArgs.setInputs(arguments, prompt, context);

        if (StringUtils.isBlank(parsedArgs.getDirectory())) {
            throw new FailedTool("You must provide a directory to scan");
        }

        return Try
                .of(() -> getFiles(parsedArgs.getDirectory()))
                .map(this::convertFilesToSummaries)
                .get();
    }

    @Override
    public RagMultiDocumentContext<Void> call(
            final Map<String, String> context,
            final String prompt,
            final List<ToolArgs> arguments) {

        final String debugArgs = debugToolArgs.debugArgs(arguments);

        final Try<RagMultiDocumentContext<Void>> result = Try
                .of(() -> getContext(context, prompt, arguments))
                .map(list -> listLimiter.limitListContent(
                        list,
                        RagDocumentContext::document,
                        modelConfig.getCalculatedContextWindowChars()))
                .map(ragDocs -> mergeContext(ragDocs, context, debugArgs))
                // Make sure we had some content for the prompt
                .mapTry(mergedContext ->
                        validateString.throwIfEmpty(mergedContext, RagMultiDocumentContext::combinedDocument))
                .map(ragDoc -> ragDoc.updateDocument(
                        promptBuilderSelector.getPromptBuilder(modelConfig.getCalculatedModel(context)).buildFinalPrompt(
                                INSTRUCTIONS,
                                promptBuilderSelector.getPromptBuilder(
                                        modelConfig.getCalculatedModel(context)).buildContextPrompt(
                                        "Individual File Answer", ragDoc.combinedDocument()),
                                prompt)))
                .map(ragDoc -> ollamaClient.callOllamaWithCache(
                        ragDoc,
                        modelConfig.getCalculatedModel(context),
                        getName(),
                        modelConfig.getCalculatedContextWindow()));

        // Handle mapFailure in isolation to avoid intellij making a mess of the formatting
        // https://github.com/vavr-io/vavr/issues/2411
        return result.mapFailure(
                        API.Case(API.$(instanceOf(EmptyString.class)),
                                throwable -> new FailedTool("No files found for " + parsedArgs.getDirectory() + debugArgs)),
                        API.Case(API.$(),
                                throwable -> new FailedTool("Failed to get file contents: " + throwable.getMessage() + "\n" + debugArgs)))
                .get();
    }

    public List<String> getFiles(final String directory) throws IOException {
        try (final Stream<Path> paths = Files.walk(Paths.get(directory))) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> parsedArgs.getExcluded() == null || !parsedArgs.getExcluded().contains(path.getFileName().toString()))
                    .map(Path::toString)
                    .collect(Collectors.toList());
        }
    }

    private RagMultiDocumentContext<Void> mergeContext(
            final List<RagDocumentContext<Void>> ragContext,
            final Map<String, String> context,
            final String debug) {
        return new RagMultiDocumentContext<>(
                ragContext.stream()
                        .map(ragDoc -> promptBuilderSelector.getPromptBuilder(
                                        modelConfig.getCalculatedModel(context))
                                .buildContextPrompt(getContextLabel(), ragDoc.document()))
                        .collect(Collectors.joining("\n")),
                ragContext,
                debug);
    }

    private List<RagDocumentContext<Void>> convertFilesToSummaries(final List<String> files) {
        return files
                .stream()
                .limit(parsedArgs.getMaxFiles() == -1 ? Long.MAX_VALUE : parsedArgs.getMaxFiles())
                .map(this::getFileContext)
                .filter(Objects::nonNull)
                .toList();
    }

    private RagDocumentContext<Void> getFileContext(final String file) {
        if (parsedArgs.getSummarizeIndividualFiles()) {
            return getFileSummary(file);
        }

        return getRawFile(file);
    }

    private RagDocumentContext<Void> getRawFile(final String file) {
        logger.info("DirectoryScan processing file: " + file);

        /*
             Each individual file is converted to text and used to answer the prompt.
             The combined answers are then used to answer the prompt again.
         */
        final String contents = Arrays.stream(fileToText.convert(file).split("\n"))
                .map(rawContents -> documentTrimmer.trimDocument(
                        rawContents,
                        parsedArgs.getKeywords(),
                        parsedArgs.getKeywordWindow()))
                .map(StringUtils::trim)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.joining("\n"));

        if (StringUtils.isBlank(contents)) {
            return null;
        }

        if (parsedArgs.getDisableLinks()) {
            return new RagDocumentContext<>(getContextLabel(), contents, List.of());
        }

        return new RagDocumentContext<>(
                getContextLabel(),
                contents,
                sentenceSplitter.splitDocument(contents, 10)
                        .stream()
                        .map(sentenceVectorizer::vectorize)
                        .toList(),
                file,
                null,
                "[" + file + "](file://" + file + ")");
    }

    /**
     * I could not get an LLM to provide a useful summary of a collection of Git Diffs. They would focus on the last diff
     * or hallucinate a bunch of random release notes. Instead, each diff is summarised individually and then combined
     * into a single document to be summarised again.
     */
    private RagDocumentContext<Void> getFileSummary(final String file) {
        logger.info("DirectoryScan processing file: " + file);

        /*
             Each individual file is converted to text and used to answer the prompt.
             The combined answers are then used to answer the prompt again.
         */
        final String contents = Arrays.stream(fileToText.convert(file).split("\n"))
                .map(rawContents -> documentTrimmer.trimDocument(
                        rawContents,
                        parsedArgs.getKeywords(),
                        parsedArgs.getKeywordWindow()))
                .map(StringUtils::trim)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.joining("\n"));

        if (StringUtils.isBlank(contents)) {
            return null;
        }

        final String summary = localStorage.getOrPutString(
                this.getName(),
                "File",
                DigestUtils.sha256Hex(parsedArgs.getIndividualDocumentPrompt() + contents),
                () -> getFileSummary(contents, parsedArgs));

        if (parsedArgs.getDisableLinks()) {
            return new RagDocumentContext<>(getContextLabel(), summary, List.of());
        }

        return new RagDocumentContext<>(
                getContextLabel(),
                summary,
                sentenceSplitter.splitDocument(summary, 10)
                        .stream()
                        .map(sentenceVectorizer::vectorize)
                        .toList(),
                file,
                null,
                "[" + file + "](file://" + file + ")");
    }

    /**
     * Use the LLM to answer the prompt based on the contents of the file.
     */
    private String getFileSummary(final String contents, final Arguments parsedArgs) {
        return Try.withResources(ClientBuilder::newClient)
                .of(client -> ollamaClient.callOllama(
                        client,
                        new OllamaGenerateBody(
                                parsedArgs.getFileCustomModel(),
                                promptBuilderSelector.getPromptBuilder(parsedArgs.getFileCustomModel()).buildFinalPrompt(
                                        FILE_INSTRUCTIONS,
                                        promptBuilderSelector.getPromptBuilder(
                                                parsedArgs.getFileCustomModel()).buildContextPrompt(
                                                getContextLabel(), contents),
                                        parsedArgs.getIndividualDocumentPrompt()),
                                false,
                                new OllamaGenerateBodyOptions(parsedArgs.getFileContextWindow()))))
                .get()
                .response();
    }
}

/**
 * Exposes the arguments for the DirectoryScan tool.
 */
@ApplicationScoped
class Arguments {
    @Inject
    @ConfigProperty(name = "sb.ollama.filemodel")
    private Optional<String> filemodel;

    @Inject
    @ConfigProperty(name = "sb.ollama.filewindow")
    private Optional<String> fileContextWindow;

    @Inject
    @ConfigProperty(name = "sb.directoryscan.directory")
    private Optional<String> directory;

    @Inject
    @ConfigProperty(name = "sb.directoryscan.maxfiles")
    private Optional<String> maxfiles;

    @Inject
    @ConfigProperty(name = "sb.directoryscan.exclude")
    private Optional<String> exclude;

    @Inject
    @ConfigProperty(name = "sb.directoryscan.individualdocumentprompt")
    private Optional<String> documentPrompt;

    @Inject
    @ConfigProperty(name = "sb.directoryscan.keywords")
    private Optional<String> keywords;

    @Inject
    @ConfigProperty(name = "sb.directoryscan.keywordwindow")
    private Optional<String> keywordWindow;

    @Inject
    @ConfigProperty(name = "sb.directoryscan.disablelinks")
    private Optional<String> disableLinks;

    @Inject
    @ConfigProperty(name = "sb.directoryscan.summarizeindividualfiles")
    private Optional<String> summarizeIndividualFiles;

    @Inject
    private ArgsAccessor argsAccessor;

    @Inject
    private ModelConfig modelConfig;

    @Inject
    private ValidateString validateString;

    private List<ToolArgs> arguments;

    private String prompt;

    private Map<String, String> context;

    public void setInputs(final List<ToolArgs> arguments, final String prompt, final Map<String, String> context) {
        this.arguments = arguments;
        this.prompt = prompt;
        this.context = context;
    }

    public String getDirectory() {
        return argsAccessor.getArgument(
                directory::get,
                arguments,
                context,
                DirectoryScan.DIRECTORYSCAN_DIRECTORY,
                "directoryscan_directory",
                "");
    }

    public int getMaxFiles() {
        final String stringValue = argsAccessor.getArgument(
                maxfiles::get,
                arguments,
                context,
                DirectoryScan.DIRECTORYSCAN_MAX_FILES,
                "directoryscan_maxfiles",
                "-1");

        return NumberUtils.toInt(stringValue, -1);
    }

    public String getFileCustomModel() {
        return argsAccessor.getArgument(
                filemodel::get,
                arguments,
                context,
                DirectoryScan.DIRECTORYSCAN_FILE_CUSTOM_MODEL,
                "directoryscan_file_custom_model",
                filemodel.orElse(modelConfig.getCalculatedModel(context)));
    }

    public String getIndividualDocumentPrompt() {
        return argsAccessor.getArgument(
                documentPrompt::get,
                arguments,
                context,
                DirectoryScan.DIRECTORYSCAN_INDIVIDUAL_DOCUMENT_PROMPT,
                "directoryscan_individual_document_prompt",
                prompt);
    }

    @Nullable
    public Integer getFileContextWindow() {
        final String stringValue = argsAccessor.getArgument(
                fileContextWindow::get,
                arguments,
                context,
                DirectoryScan.DIRECTORYSCAN_FILE_CONTENT_WINDOW,
                "directoryscan_file_content_window",
                Constants.DEFAULT_CONTENT_WINDOW + "");

        return NumberUtils.toInt(stringValue, Constants.DEFAULT_CONTENT_WINDOW);
    }

    @Nullable
    public List<String> getExcluded() {
        return argsAccessor.getArgumentList(
                exclude::get,
                arguments,
                context,
                DirectoryScan.DIRECTORYSCAN_EXCLUDE_FILES,
                "directoryscan_exclude_files",
                "");
    }

    public List<String> getKeywords() {
        return argsAccessor.getArgumentList(
                keywords::get,
                arguments,
                context,
                DirectoryScan.DIRECTORYSCAN_SUMMARIZE_KEYWORDS,
                "directoryscan_keywords",
                "");
    }

    public int getKeywordWindow() {

        final String stringValue = argsAccessor.getArgument(
                keywordWindow::get,
                arguments,
                context,
                DirectoryScan.DIRECTORYSCAN_SUMMARIZE_KEYWORD_WINDOW,
                "directoryscan_summarize_keyword_window",
                Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH + "");

        return NumberUtils.toInt(stringValue, Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH);
    }

    public boolean getDisableLinks() {
        final String stringValue = argsAccessor.getArgument(
                disableLinks::get,
                arguments,
                context,
                DirectoryScan.DIRECTORYSCAN_DISABLELINKS_ARG,
                "directoryscan_disable_links",
                "false");

        return BooleanUtils.toBoolean(stringValue);
    }

    public boolean getSummarizeIndividualFiles() {
        final String stringValue = argsAccessor.getArgument(
                summarizeIndividualFiles::get,
                arguments,
                context,
                DirectoryScan.DIRECTORYSCAN_SUMMARIZE_INDIVIDUAL_FILES_ARG,
                "directoryscan_summarize_individual_files",
                "true");

        return BooleanUtils.toBoolean(stringValue);
    }
}

