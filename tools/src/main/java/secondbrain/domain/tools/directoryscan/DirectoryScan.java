package secondbrain.domain.tools.directoryscan;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jooq.lambda.Seq;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.args.Argument;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.context.SentenceSplitter;
import secondbrain.domain.context.SentenceVectorizer;
import secondbrain.domain.converter.FileToText;
import secondbrain.domain.debug.DebugToolArgs;
import secondbrain.domain.exceptionhandling.ExceptionMapping;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.files.PathSpec;
import secondbrain.domain.hooks.HooksContainer;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.limit.DocumentTrimmer;
import secondbrain.domain.limit.TrimResult;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.validate.ValidateList;
import secondbrain.domain.validate.ValidateString;
import secondbrain.infrastructure.llm.LlmClient;

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

/**
 * Scans the files in a directory and answers questions about them. This is useful when you have a bunch of reports
 * or other files that contain information that you want to query.
 */
@ApplicationScoped
public class DirectoryScan implements Tool<Void> {
    public static final String DIRECTORYSCAN_SUMMARIZE_INDIVIDUAL_FILES_ARG = "summarizeIndividualFiles";
    public static final String DIRECTORYSCAN_SUMMARIZE_KEYWORD_WINDOW = "keywordWindow";
    public static final String DIRECTORYSCAN_SUMMARIZE_KEYWORDS = "keywords";
    public static final String DIRECTORYSCAN_EXCLUDE_FILES = "excludeFiles";
    public static final String DIRECTORYSCAN_PATHSPEC = "pathspec";
    public static final String DIRECTORYSCAN_INDIVIDUAL_DOCUMENT_PROMPT = "individualDocumentPrompt";
    public static final String DIRECTORYSCAN_MAX_FILES = "maxfiles";
    public static final String DIRECTORYSCAN_DIRECTORY = "directory";
    public static final String DIRECTORYSCAN_ENTITY_NAME_CONTEXT_ARG = "entityName";
    public static final String PREPROCESSOR_HOOKS_CONTEXT_ARG = "preProcessorHooks";
    public static final String PREINITIALIZATION_HOOKS_CONTEXT_ARG = "preInitializationHooks";
    public static final String POSTINFERENCE_HOOKS_CONTEXT_ARG = "postInferenceHooks";

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
    private DirectoryScanConfig config;

    @Inject
    private DebugToolArgs debugToolArgs;

    @Inject
    @Preferred
    private LlmClient llmClient;

    @Inject
    private ValidateString validateString;

    @Inject
    private ValidateList validateList;

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

    @Inject
    private PathSpec pathSpec;

    @Inject
    private ExceptionMapping exceptionMapping;

    @Inject
    private HooksContainer hooksContainer;

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

        if (parsedArgs.getDirectory().isEmpty()) {
            throw new InternalFailure("You must provide a directory to scan");
        }

        // Get preinitialization hooks before ragdocs
        final List<RagDocumentContext<Void>> preinitHooks = Seq.seq(hooksContainer.getMatchingPreProcessorHooks(parsedArgs.getPreinitializationHooks()))
                .foldLeft(List.of(), (docs, hook) -> hook.process(getName(), docs));

        final List<RagDocumentContext<Void>> ragDocs = Try
                .of(() -> getFiles(parsedArgs))
                .map(files -> convertFilesToSummaries(files, parsedArgs, environmentSettings))
                .get();

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

        final DirectoryScanConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        final String debugArgs = debugToolArgs.debugArgs(arguments);

        final Try<RagMultiDocumentContext<Void>> result = Try
                .of(() -> getContext(environmentSettings, prompt, arguments))
                // Make sure we had some content for the prompt
                .map(validateList::throwIfEmpty)
                .map(ragDocs -> mergeContext(prompt, INSTRUCTIONS, ragDocs, debugArgs))
                .map(ragDoc -> llmClient.callWithCache(
                        ragDoc,
                        environmentSettings,
                        getName()));

        final RagMultiDocumentContext<Void> mappedResult = exceptionMapping.map(result).get();

        // Apply postinference hooks
        return Seq.seq(hooksContainer.getMatchingPostInferenceHooks(parsedArgs.getPostInferenceHooks()))
                .foldLeft(mappedResult, (docs, hook) -> hook.process(getName(), docs));
    }

    private List<String> getFiles(final DirectoryScanConfig.LocalArguments parsedArgs) {
        return parsedArgs.getDirectory()
                .stream()
                .flatMap(pathname -> getFiles(pathname, parsedArgs).get().stream())
                .toList();
    }

    private Try<List<String>> getFiles(final String pathname, final DirectoryScanConfig.LocalArguments parsedArgs) {
        return Try.withResources(() -> Files.walk(Paths.get(pathname)))
                .of(paths -> paths.filter(Files::isRegularFile)
                        .filter(path -> parsedArgs.getExcluded() == null || !parsedArgs.getExcluded().contains(path.getFileName().toString()))
                        .filter(path -> parsedArgs.getPathSpec() == null || parsedArgs.getPathSpec().stream()
                                .anyMatch(spec -> pathSpec.matches(spec, path.getFileName().toString())))
                        .map(Path::toString)
                        .collect(Collectors.toList()));
    }


    private RagMultiDocumentContext<Void> mergeContext(
            final String prompt,
            final String instructions,
            final List<RagDocumentContext<Void>> ragContext,
            final String debug) {
        return new RagMultiDocumentContext<>(
                prompt,
                instructions,
                ragContext,
                debug);
    }

    private List<RagDocumentContext<Void>> convertFilesToSummaries(final List<String> files, final DirectoryScanConfig.LocalArguments parsedArgs, final Map<String, String> environmentSettings) {
        return files
                .stream()
                .limit(parsedArgs.getMaxFiles() == -1 ? Long.MAX_VALUE : parsedArgs.getMaxFiles())
                .map(file -> getFileContext(file, parsedArgs, environmentSettings))
                .filter(Objects::nonNull)
                .toList();
    }

    private RagDocumentContext<Void> getFileContext(final String file, final DirectoryScanConfig.LocalArguments parsedArgs, final Map<String, String> environmentSettings) {
        if (parsedArgs.getSummarizeIndividualFiles()) {
            return getFileSummary(file, parsedArgs, environmentSettings);
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

        return new RagDocumentContext<>(
                getName(),
                getContextLabel() + " " + Paths.get(file).getFileName(),
                trimResult.document(),
                sentenceVectorizer.vectorize(sentenceSplitter.splitDocument(trimResult.document(), 10), parsedArgs.getEntity()),
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
    private RagDocumentContext<Void> getFileSummary(final String file, final DirectoryScanConfig.LocalArguments parsedArgs, final Map<String, String> environmentSettings) {
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
                        () -> getFileSummaryLlm(trimResult.document(), parsedArgs, environmentSettings))
                .result();

        return new RagDocumentContext<>(
                getName(),
                getContextLabel() + " " + Paths.get(file).getFileName(),
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
    private String getFileSummaryLlm(final String contents, final DirectoryScanConfig.LocalArguments parsedArgs, final Map<String, String> environmentSettings) {
        final RagDocumentContext<String> context = new RagDocumentContext<>(
                getName(),
                getContextLabel(),
                contents,
                List.of()
        );

        return llmClient.callWithCache(
                new RagMultiDocumentContext<>(
                        parsedArgs.getIndividualDocumentPrompt(),
                        FILE_INSTRUCTIONS,
                        List.of(context)),
                environmentSettings,
                getName()
        ).getResponse();
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
    @ConfigProperty(name = "sb.directoryscan.pathspec")
    private Optional<String> configPathSpec;

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
    @ConfigProperty(name = "sb.directoryscan.summarizeindividualfiles")
    private Optional<String> configSummarizeIndividualFiles;

    @Inject
    @ConfigProperty(name = "sb.directoryscan.preprocessorHooks", defaultValue = "")
    private Optional<String> configPreprocessorHooks;

    @Inject
    @ConfigProperty(name = "sb.directoryscan.preinitializationHooks", defaultValue = "")
    private Optional<String> configPreinitializationHooks;

    @Inject
    @ConfigProperty(name = "sb.directoryscan.postinferenceHooks", defaultValue = "")
    private Optional<String> configPostInferenceHooks;

    @Inject
    private ArgsAccessor argsAccessor;

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

    public Optional<String> getConfigPathSpec() {
        return configPathSpec;
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

    public Optional<String> getConfigSummarizeIndividualFiles() {
        return configSummarizeIndividualFiles;
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

        public List<String> getDirectory() {
            return getArgsAccessor().getArgumentList(
                            getConfigDirectory()::get,
                            arguments,
                            context,
                            DirectoryScan.DIRECTORYSCAN_DIRECTORY,
                            DirectoryScan.DIRECTORYSCAN_DIRECTORY,
                            "")
                    .stream()
                    .map(Argument::value)
                    .toList();
        }

        public int getMaxFiles() {
            final String stringValue = getArgsAccessor().getArgument(
                    getConfigMaxfiles()::get,
                    arguments,
                    context,
                    DirectoryScan.DIRECTORYSCAN_MAX_FILES,
                    DirectoryScan.DIRECTORYSCAN_MAX_FILES,
                    "-1").value();

            return NumberUtils.toInt(stringValue, -1);
        }

        public String getIndividualDocumentPrompt() {
            return getArgsAccessor().getArgument(
                    getConfigDocumentPrompt()::get,
                    arguments,
                    context,
                    DirectoryScan.DIRECTORYSCAN_INDIVIDUAL_DOCUMENT_PROMPT,
                    DirectoryScan.DIRECTORYSCAN_INDIVIDUAL_DOCUMENT_PROMPT,
                    prompt).value();
        }

        @Nullable
        public List<String> getExcluded() {
            return getArgsAccessor().getArgumentList(
                            getConfigExclude()::get,
                            arguments,
                            context,
                            DirectoryScan.DIRECTORYSCAN_EXCLUDE_FILES,
                            DirectoryScan.DIRECTORYSCAN_EXCLUDE_FILES,
                            "")
                    .stream()
                    .map(Argument::value)
                    .toList();
        }

        @Nullable
        public List<String> getPathSpec() {
            return getArgsAccessor().getArgumentList(
                            getConfigPathSpec()::get,
                            arguments,
                            context,
                            DirectoryScan.DIRECTORYSCAN_PATHSPEC,
                            DirectoryScan.DIRECTORYSCAN_PATHSPEC,
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
                            DirectoryScan.DIRECTORYSCAN_SUMMARIZE_KEYWORDS,
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
                    DirectoryScan.DIRECTORYSCAN_SUMMARIZE_KEYWORD_WINDOW,
                    Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH + "").value();

            return NumberUtils.toInt(stringValue, Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH);
        }

        public boolean getSummarizeIndividualFiles() {
            final String stringValue = getArgsAccessor().getArgument(
                    getConfigSummarizeIndividualFiles()::get,
                    arguments,
                    context,
                    DirectoryScan.DIRECTORYSCAN_SUMMARIZE_INDIVIDUAL_FILES_ARG,
                    DirectoryScan.DIRECTORYSCAN_SUMMARIZE_INDIVIDUAL_FILES_ARG,
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

        public String getPreprocessingHooks() {
            return getArgsAccessor().getArgument(
                    getConfigPreprocessorHooks()::get,
                    arguments,
                    context,
                    DirectoryScan.PREPROCESSOR_HOOKS_CONTEXT_ARG,
                    DirectoryScan.PREPROCESSOR_HOOKS_CONTEXT_ARG,
                    "").value();
        }

        public String getPreinitializationHooks() {
            return getArgsAccessor().getArgument(
                    getConfigPreinitializationHooks()::get,
                    arguments,
                    context,
                    DirectoryScan.PREINITIALIZATION_HOOKS_CONTEXT_ARG,
                    DirectoryScan.PREINITIALIZATION_HOOKS_CONTEXT_ARG,
                    "").value();
        }

        public String getPostInferenceHooks() {
            return getArgsAccessor().getArgument(
                    getConfigPostInferenceHooks()::get,
                    arguments,
                    context,
                    DirectoryScan.POSTINFERENCE_HOOKS_CONTEXT_ARG,
                    DirectoryScan.POSTINFERENCE_HOOKS_CONTEXT_ARG,
                    "").value();
        }
    }
}
