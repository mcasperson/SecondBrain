package secondbrain.domain.tools.directoryscan;

import io.vavr.API;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.args.Argument;
import secondbrain.domain.config.ModelConfig;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.context.SentenceSplitter;
import secondbrain.domain.context.SentenceVectorizer;
import secondbrain.domain.converter.FileToText;
import secondbrain.domain.debug.DebugToolArgs;
import secondbrain.domain.exceptions.EmptyString;
import secondbrain.domain.exceptions.ExternalFailure;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.limit.DocumentTrimmer;
import secondbrain.domain.limit.ListLimiter;
import secondbrain.domain.limit.TrimResult;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.prompt.PromptBuilderSelector;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.validate.ValidateString;
import secondbrain.infrastructure.ollama.OllamaClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
    public static final String DIRECTORYSCAN_ENTITY_NAME_CONTEXT_ARG = "entityName";

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
    private DirectoryScanConfig config;
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
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {

        final DirectoryScanConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        if (StringUtils.isBlank(parsedArgs.getDirectory())) {
            throw new InternalFailure("You must provide a directory to scan");
        }

        return Try
                .of(() -> getFiles(parsedArgs))
                .map(files -> convertFilesToSummaries(files, parsedArgs))
                .get();
    }

    @Override
    public RagMultiDocumentContext<Void> call(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {

        final DirectoryScanConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        final String debugArgs = debugToolArgs.debugArgs(arguments);

        final Try<RagMultiDocumentContext<Void>> result = Try
                .of(() -> getContext(environmentSettings, prompt, arguments))
                .map(list -> listLimiter.limitListContent(
                        list,
                        RagDocumentContext::document,
                        modelConfig.getCalculatedContextWindowChars()))
                .map(ragDocs -> mergeContext(ragDocs, environmentSettings, debugArgs))
                // Make sure we had some content for the prompt
                .mapTry(mergedContext ->
                        validateString.throwIfEmpty(mergedContext, RagMultiDocumentContext::combinedDocument))
                .map(ragDoc -> ragDoc.updateDocument(
                        promptBuilderSelector.getPromptBuilder(modelConfig.getCalculatedModel(environmentSettings)).buildFinalPrompt(
                                INSTRUCTIONS,
                                promptBuilderSelector.getPromptBuilder(
                                        modelConfig.getCalculatedModel(environmentSettings)).buildContextPrompt(
                                        "Individual File Answer", ragDoc.combinedDocument()),
                                prompt)))
                .map(ragDoc -> ollamaClient.callOllamaWithCache(
                        ragDoc,
                        modelConfig.getCalculatedModel(environmentSettings),
                        getName(),
                        modelConfig.getCalculatedContextWindow()));

        // Handle mapFailure in isolation to avoid intellij making a mess of the formatting
        // https://github.com/vavr-io/vavr/issues/2411
        return result.mapFailure(
                        API.Case(API.$(instanceOf(InternalFailure.class)), throwable -> throwable),
                        API.Case(API.$(instanceOf(EmptyString.class)),
                                throwable -> new InternalFailure("No files found for " + parsedArgs.getDirectory() + debugArgs)),
                        API.Case(API.$(),
                                throwable -> new ExternalFailure("Failed to get file contents: " + throwable.getMessage() + "\n" + debugArgs)))
                .get();
    }

    public List<String> getFiles(final DirectoryScanConfig.LocalArguments parsedArgs) throws IOException {
        try (final Stream<Path> paths = Files.walk(Paths.get(parsedArgs.getDirectory()))) {
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

    private List<RagDocumentContext<Void>> convertFilesToSummaries(final List<String> files, final DirectoryScanConfig.LocalArguments parsedArgs) {
        return files
                .stream()
                .limit(parsedArgs.getMaxFiles() == -1 ? Long.MAX_VALUE : parsedArgs.getMaxFiles())
                .map(file -> getFileContext(file, parsedArgs))
                .filter(Objects::nonNull)
                .toList();
    }

    private RagDocumentContext<Void> getFileContext(final String file, final DirectoryScanConfig.LocalArguments parsedArgs) {
        if (parsedArgs.getSummarizeIndividualFiles()) {
            return getFileSummary(file, parsedArgs);
        }

        return getRawFile(file, parsedArgs);
    }

    private RagDocumentContext<Void> getRawFile(final String file, final DirectoryScanConfig.LocalArguments parsedArgs) {
        logger.info("DirectoryScan processing file: " + file);

        /*
             Each individual file is converted to text and used to answer the prompt.
             The combined answers are then used to answer the prompt again.
         */
        final TrimResult trimResult = documentTrimmer.trimDocumentToKeywords(
                fileToText.convert(file),
                parsedArgs.getKeywords(),
                parsedArgs.getKeywordWindow());

        if (validateString.isEmpty(trimResult.document())) {
            return null;
        }

        if (parsedArgs.getDisableLinks()) {
            return new RagDocumentContext<>(getContextLabel(), trimResult.document(), List.of(), null, null, null, trimResult.keywordMatches());
        }

        return new RagDocumentContext<>(
                getContextLabel(),
                trimResult.document(),
                sentenceSplitter.splitDocument(trimResult.document(), 10)
                        .stream()
                        .map(sentence -> sentenceVectorizer.vectorize(sentence, parsedArgs.getEntity()))
                        .toList(),
                file,
                null,
                "[" + file + "](file://" + file + ")",
                trimResult.keywordMatches());
    }

    /**
     * I could not get an LLM to provide a useful summary of a collection of Git Diffs. They would focus on the last diff
     * or hallucinate a bunch of random release notes. Instead, each diff is summarised individually and then combined
     * into a single document to be summarised again.
     */
    private RagDocumentContext<Void> getFileSummary(final String file, final DirectoryScanConfig.LocalArguments parsedArgs) {
        logger.info("DirectoryScan processing file: " + file);

        /*
             Each individual file is converted to text and used to answer the prompt.
             The combined answers are then used to answer the prompt again.
         */
        final TrimResult trimResult = documentTrimmer.trimDocumentToKeywords(
                fileToText.convert(file),
                parsedArgs.getKeywords(),
                parsedArgs.getKeywordWindow());

        if (validateString.isEmpty(trimResult.document())) {
            return null;
        }

        final String summary = localStorage.getOrPutString(
                this.getName(),
                "File",
                DigestUtils.sha256Hex(parsedArgs.getIndividualDocumentPrompt() + trimResult.document()),
                () -> getFileSummaryLlm(trimResult.document(), parsedArgs));

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
                "[" + file + "](file://" + file + ")",
                trimResult.keywordMatches());
    }

    /**
     * Use the LLM to answer the prompt based on the contents of the file.
     */
    private String getFileSummaryLlm(final String contents, final DirectoryScanConfig.LocalArguments parsedArgs) {
        return ollamaClient.callOllamaWithCache(
                new RagMultiDocumentContext<>(promptBuilderSelector.getPromptBuilder(parsedArgs.getFileCustomModel()).buildFinalPrompt(
                        FILE_INSTRUCTIONS,
                        promptBuilderSelector.getPromptBuilder(
                                parsedArgs.getFileCustomModel()).buildContextPrompt(
                                getContextLabel(), contents),
                        parsedArgs.getIndividualDocumentPrompt())),
                parsedArgs.getFileCustomModel(),
                getName(),
                parsedArgs.getFileContextWindow()
        ).combinedDocument();
    }
}

/**
 * Exposes the arguments for the DirectoryScan tool.
 */
@ApplicationScoped
class DirectoryScanConfig {
    @Inject
    @ConfigProperty(name = "sb.ollama.filemodel")
    private Optional<String> configFileModel;

    @Inject
    @ConfigProperty(name = "sb.ollama.filewindow")
    private Optional<String> configFileContextWindow;

    @Inject
    @ConfigProperty(name = "sb.directoryscan.directory")
    private Optional<String> configDirectory;

    @Inject
    @ConfigProperty(name = "sb.directoryscan.maxfiles")
    private Optional<String> configMaxfiles;

    @Inject
    @ConfigProperty(name = "sb.directoryscan.exclude")
    private Optional<String> configExclude;

    @Inject
    @ConfigProperty(name = "sb.directoryscan.individualdocumentprompt")
    private Optional<String> configDocumentPrompt;

    @Inject
    @ConfigProperty(name = "sb.directoryscan.keywords")
    private Optional<String> configKeywords;

    @Inject
    @ConfigProperty(name = "sb.directoryscan.keywordwindow")
    private Optional<String> configKeywordWindow;

    @Inject
    @ConfigProperty(name = "sb.directoryscan.disablelinks")
    private Optional<String> configDisableLinks;

    @Inject
    @ConfigProperty(name = "sb.directoryscan.summarizeindividualfiles")
    private Optional<String> configSummarizeIndividualFiles;

    @Inject
    private ArgsAccessor argsAccessor;

    @Inject
    private ModelConfig modelConfig;

    @Inject
    private ValidateString validateString;

    public Optional<String> getConfigFileModel() {
        return configFileModel;
    }

    public Optional<String> getConfigFileContextWindow() {
        return configFileContextWindow;
    }

    public Optional<String> getConfigDirectory() {
        return configDirectory;
    }

    public Optional<String> getConfigMaxfiles() {
        return configMaxfiles;
    }

    public Optional<String> getConfigExclude() {
        return configExclude;
    }

    public Optional<String> getConfigDocumentPrompt() {
        return configDocumentPrompt;
    }

    public Optional<String> getConfigKeywords() {
        return configKeywords;
    }

    public Optional<String> getConfigKeywordWindow() {
        return configKeywordWindow;
    }

    public Optional<String> getConfigDisableLinks() {
        return configDisableLinks;
    }

    public Optional<String> getConfigSummarizeIndividualFiles() {
        return configSummarizeIndividualFiles;
    }

    public ArgsAccessor getArgsAccessor() {
        return argsAccessor;
    }

    public ModelConfig getModelConfig() {
        return modelConfig;
    }

    public ValidateString getValidateString() {
        return validateString;
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

        public String getDirectory() {
            return getArgsAccessor().getArgument(
                    getConfigDirectory()::get,
                    arguments,
                    context,
                    DirectoryScan.DIRECTORYSCAN_DIRECTORY,
                    "directoryscan_directory",
                    "").value();
        }

        public int getMaxFiles() {
            final String stringValue = getArgsAccessor().getArgument(
                    getConfigMaxfiles()::get,
                    arguments,
                    context,
                    DirectoryScan.DIRECTORYSCAN_MAX_FILES,
                    "directoryscan_maxfiles",
                    "-1").value();

            return NumberUtils.toInt(stringValue, -1);
        }

        public String getFileCustomModel() {
            return getArgsAccessor().getArgument(
                    getConfigFileModel()::get,
                    arguments,
                    context,
                    DirectoryScan.DIRECTORYSCAN_FILE_CUSTOM_MODEL,
                    "directoryscan_file_custom_model",
                    getConfigFileModel().orElse(getModelConfig().getCalculatedModel(context))).value();
        }

        public String getIndividualDocumentPrompt() {
            return getArgsAccessor().getArgument(
                    getConfigDocumentPrompt()::get,
                    arguments,
                    context,
                    DirectoryScan.DIRECTORYSCAN_INDIVIDUAL_DOCUMENT_PROMPT,
                    "directoryscan_individual_document_prompt",
                    prompt).value();
        }

        @Nullable
        public Integer getFileContextWindow() {
            final String stringValue = getArgsAccessor().getArgument(
                    getConfigFileContextWindow()::get,
                    arguments,
                    context,
                    DirectoryScan.DIRECTORYSCAN_FILE_CONTENT_WINDOW,
                    "directoryscan_file_content_window",
                    Constants.DEFAULT_CONTENT_WINDOW + "").value();

            return NumberUtils.toInt(stringValue, Constants.DEFAULT_CONTENT_WINDOW);
        }

        @Nullable
        public List<String> getExcluded() {
            return getArgsAccessor().getArgumentList(
                            getConfigExclude()::get,
                            arguments,
                            context,
                            DirectoryScan.DIRECTORYSCAN_EXCLUDE_FILES,
                            "directoryscan_exclude_files",
                            "")
                    .stream()
                    .map(Argument::value)
                    .toList();
        }

        public List<String> getKeywords() {
            return getArgsAccessor().getArgumentList(
                            getConfigKeywords()::get,
                            arguments,
                            context,
                            DirectoryScan.DIRECTORYSCAN_SUMMARIZE_KEYWORDS,
                            "directoryscan_keywords",
                            "")
                    .stream()
                    .map(Argument::value)
                    .toList();
        }

        public int getKeywordWindow() {

            final String stringValue = getArgsAccessor().getArgument(
                    getConfigKeywordWindow()::get,
                    arguments,
                    context,
                    DirectoryScan.DIRECTORYSCAN_SUMMARIZE_KEYWORD_WINDOW,
                    "directoryscan_summarize_keyword_window",
                    Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH + "").value();

            return NumberUtils.toInt(stringValue, Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH);
        }

        public boolean getDisableLinks() {
            final String stringValue = getArgsAccessor().getArgument(
                    getConfigDisableLinks()::get,
                    arguments,
                    context,
                    DirectoryScan.DIRECTORYSCAN_DISABLELINKS_ARG,
                    "directoryscan_disable_links",
                    "false").value();

            return BooleanUtils.toBoolean(stringValue);
        }

        public boolean getSummarizeIndividualFiles() {
            final String stringValue = getArgsAccessor().getArgument(
                    getConfigSummarizeIndividualFiles()::get,
                    arguments,
                    context,
                    DirectoryScan.DIRECTORYSCAN_SUMMARIZE_INDIVIDUAL_FILES_ARG,
                    "directoryscan_summarize_individual_files",
                    "true").value();

            return BooleanUtils.toBoolean(stringValue);
        }

        public String getEntity() {
            return getArgsAccessor().getArgument(
                    null,
                    null,
                    context,
                    null,
                    DirectoryScan.DIRECTORYSCAN_ENTITY_NAME_CONTEXT_ARG,
                    "").value();
        }
    }
}

