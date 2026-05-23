package secondbrain.domain.tools.publicfile;

import com.google.common.collect.ImmutableList;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jooq.lambda.Seq;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.args.Argument;
import secondbrain.domain.config.LocalConfigKeywordsEntity;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.converter.FileToText;
import secondbrain.domain.exceptionhandling.ExceptionMapping;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.hooks.HooksContainer;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.limit.DocumentTrimmer;
import secondbrain.domain.objects.ToStringGenerator;
import secondbrain.domain.processing.DataToRagDoc;
import secondbrain.domain.reader.FileReader;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.tools.CommonArguments;
import secondbrain.domain.tools.keyword.Keywords;
import secondbrain.domain.tools.publicfile.model.FileContents;
import secondbrain.infrastructure.llm.LlmClient;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A tool that downloads a public file from HTTP or accesses a file from a local disk
 * and uses it as the context for a query.
 */
@ApplicationScoped
public class PublicFile implements Tool<Void> {

    public static final String PUBLICWEB_URL_ARG = "url";

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
    private DataToRagDoc dataToRagDoc;

    @Inject
    private FileReader fileReader;

    @Inject
    @Preferred
    private LlmClient llmClient;

    @Inject
    private PublicWebConfig config;

    @Inject
    private FileToText fileToText;

    @Inject
    private ExceptionMapping exceptionMapping;

    @Inject
    private HooksContainer hooksContainer;

    @Inject
    private DocumentTrimmer documentTrimmer;

    @Override
    public String getName() {
        return PublicFile.class.getSimpleName();
    }

    @Override
    public String getDescription() {
        return "Downloads a document from a supplied URL and queries it";
    }

    @Override
    public List<ToolArguments> getArguments() {
        return ImmutableList.of(new ToolArguments(
                        PUBLICWEB_URL_ARG,
                        "The URL of the document to download",
                        ""),
                new ToolArguments(CommonArguments.KEYWORD_WINDOW_ARG, "The window size around any matching keywords", ""),
                new ToolArguments(CommonArguments.AUTO_GENERATE_KEYWORDS_ARG, "Set to true to automatically generate keywords from the prompt using the Keywords LLM tool", "false"),
                new ToolArguments(
                        CommonArguments.KEYWORDS_ARG,
                        "The keywords to limit the document to",
                        ""));
    }

    @Override
    public String getContextLabel() {
        return "File Contents";
    }

    @Override
    public int contextHashCode(final Map<String, String> environmentSettings, final List<String> prompts, final List<ToolArgs> arguments) {
        final PublicWebConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompts, environmentSettings);
        return 31 * parsedArgs.hashCode() + prompts.hashCode();
    }

    @Override
    public List<RagDocumentContext<Void>> getContext(
            final Map<String, String> environmentSettings,
            final List<String> prompts,
            final List<ToolArgs> arguments) {
        final String prompt = prompts.isEmpty() ? "" : prompts.getFirst();
        return Try.of(() -> getContextPrivate(environmentSettings, prompt, arguments))
                .onFailure(ex -> java.util.logging.Logger.getLogger(getClass().getName()).warning("Failed to get context for " + getName() + ": " + ExceptionUtils.getRootCauseMessage(ex)))
                .getOrElse(List::of);
    }

    private List<RagDocumentContext<Void>> getContextPrivate(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {
        final PublicWebConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, List.of(prompt), environmentSettings);

        if (StringUtils.isBlank(parsedArgs.getUrl())) {
            throw new InternalFailure("You must provide a URL to download");
        }

        // Get preinitialization hooks before ragdocs
        final List<RagDocumentContext<FileContents>> preinitHooks = Seq.seq(hooksContainer.getMatchingPreProcessorHooks(parsedArgs.getPreinitializationHooks()))
                .foldLeft(List.of(), (docs, hook) -> hook.process(getName(), docs));

        final Try<String> contents = Files.isRegularFile(Paths.get(parsedArgs.getUrl()))
                ? Try.of(() -> fileToText.convert(parsedArgs.getUrl()))
                : Try.of(() -> fileReader.read(parsedArgs.getUrl()));

        final List<RagDocumentContext<FileContents>> ragDocs = contents
                .map(content -> new FileContents(parsedArgs.getUrl(), parsedArgs.getUrl(), content))
                .map(document -> dataToRagDoc.getDocumentContext(document, getName(), getContextLabel(), parsedArgs))
                .map(List::of)
                .recover(ex -> List.of())
                .get();

        // Combine preinitialization hooks with ragDocs
        final List<RagDocumentContext<FileContents>> combinedDocs = Stream.concat(preinitHooks.stream(), ragDocs.stream()).toList();

        // Apply preprocessing hooks
        return Seq.seq(hooksContainer.getMatchingPreProcessorHooks(parsedArgs.getPreprocessingHooks()))
                .foldLeft(combinedDocs, (docs, hook) -> hook.process(getName(), docs))
                .stream()
                .map(ragDoc -> ragDoc.updateDocument(documentTrimmer.trimDocumentToKeywords(ragDoc.document(), parsedArgs.getKeywords(), parsedArgs.getKeywordWindow())))
                .filter(ragDoc -> StringUtils.isNotBlank(ragDoc.document()))
                .map(RagDocumentContext::convertToRagDocumentContextVoid)
                .toList();
    }

    @Override
    public RagMultiDocumentContext<Void> call(
            final Map<String, String> environmentSettings,
            final List<String> prompts,
            final List<ToolArgs> arguments) {

        final String firstPrompt = prompts.isEmpty() ? "" : prompts.get(0);
        final List<RagDocumentContext<Void>> contextList = getContext(environmentSettings, prompts, arguments);

        final PublicWebConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompts, environmentSettings);

        if (StringUtils.isBlank(parsedArgs.getUrl())) {
            throw new InternalFailure("You must provide a URL to download");
        }

        final Try<RagMultiDocumentContext<Void>> result = Try.of(() -> contextList)
                .map(ragDoc -> new RagMultiDocumentContext<Void>(prompts, INSTRUCTIONS, ragDoc))
                .map(ragDoc -> llmClient.callWithCache(
                        ragDoc,
                        environmentSettings,
                        getName()));

        final RagMultiDocumentContext<Void> mappedResult = exceptionMapping.map(result).get();

        // Apply postinference hooks
        return Seq.seq(hooksContainer.getMatchingPostInferenceHooks(parsedArgs.getPostInferenceHooks()))
                .foldLeft(mappedResult, (docs, hook) -> hook.process(getName(), docs));
    }
}

@ApplicationScoped
class PublicWebConfig {

    @Inject
    @ConfigProperty(name = "sb.publicfile.url")
    private Optional<String> configUrl;

    @Inject
    @ConfigProperty(name = "sb.publicfile.keywords")
    private Optional<String> configKeywords;

    @Inject
    @ConfigProperty(name = "sb.publicfile.autogeneratekeywords")
    private Optional<String> configAutoGenerateKeywords;

    @Inject
    @ConfigProperty(name = "sb.publicfile.keywordwindow")
    private Optional<String> configKeywordWindow;

    @Inject
    @ConfigProperty(name = "sb.publicfile.contextFilterQuestion")
    private Optional<String> configContextFilterQuestion;

    @Inject
    @ConfigProperty(name = "sb.publicfile.preprocessorHooks", defaultValue = "")
    private Optional<String> configPreprocessorHooks;

    @Inject
    @ConfigProperty(name = "sb.publicfile.preinitializationHooks", defaultValue = "")
    private Optional<String> configPreinitializationHooks;

    @Inject
    @ConfigProperty(name = "sb.publicfile.postinferenceHooks", defaultValue = "")
    private Optional<String> configPostInferenceHooks;

    @Inject
    private ArgsAccessor argsAccessor;

    @Inject
    private ToStringGenerator toStringGenerator;

    @Inject
    private Keywords keywords;

    public Optional<String> getConfigUrl() {
        return configUrl;
    }

    public Optional<String> getConfigKeywords() {
        return configKeywords;
    }

    public Optional<String> getConfigAutoGenerateKeywords() {
        return configAutoGenerateKeywords;
    }

    public Optional<String> getConfigKeywordWindow() {
        return configKeywordWindow;
    }

    public Optional<String> getConfigContextFilterQuestion() {
        return configContextFilterQuestion;
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

    public ToStringGenerator getToStringGenerator() {
        return toStringGenerator;
    }

    public Keywords getKeywordsTool() {
        return keywords;
    }

    public class LocalArguments implements LocalConfigKeywordsEntity {
        private final List<ToolArgs> arguments;

        private final List<String> prompts;

        private final Map<String, String> context;

        public LocalArguments(final List<ToolArgs> arguments, final List<String> prompts, final Map<String, String> context) {
            this.arguments = List.copyOf(arguments);
            this.prompts = List.copyOf(prompts);
            this.context = Map.copyOf(context);
        }

        @Override
        public String toString() {
            return getToStringGenerator().generateGetterConfig(this);
        }

        @Override
        public int hashCode() {
            return getToStringGenerator().generateHashGetterConfig(this);
        }

        public String getUrl() {
            return getArgsAccessor().getArgument(
                    getConfigUrl()::get,
                    arguments,
                    context,
                    PublicFile.PUBLICWEB_URL_ARG,
                    PublicFile.PUBLICWEB_URL_ARG,
                    "").getSafeValue();
        }

        @Override
        public List<String> getKeywords() {
            final List<String> keywords = getArgsAccessor().getArgumentList(
                            getConfigKeywords()::get,
                            arguments,
                            context,
                            CommonArguments.KEYWORDS_ARG,
                            CommonArguments.KEYWORDS_ARG,
                            "")
                    .stream()
                    .map(Argument::value)
                    .toList();

            if (getAutoGenerateKeywords()) {
                return CollectionUtils.collate(keywords, getKeywordsTool().getKeywords(Map.of(), Stream.concat(prompts.stream(), Stream.of(getContextFilterQuestion())).toList(), List.of()), false);
            }

            return keywords;
        }

        public boolean getAutoGenerateKeywords() {
            final String value = getArgsAccessor().getArgument(
                    getConfigAutoGenerateKeywords()::get,
                    arguments,
                    context,
                    CommonArguments.AUTO_GENERATE_KEYWORDS_ARG,
                    CommonArguments.AUTO_GENERATE_KEYWORDS_ARG,
                    "false").getSafeValue();

            return BooleanUtils.toBoolean(value);
        }

        public String getContextFilterQuestion() {
            return getArgsAccessor().getArgument(
                            getConfigContextFilterQuestion()::get,
                            arguments,
                            context,
                            CommonArguments.CONTENT_RATING_QUESTION_ARG,
                            CommonArguments.CONTENT_RATING_QUESTION_ARG,
                            "")
                    .getSafeValue();
        }

        @Override
        public int getKeywordWindow() {
            final Argument argument = getArgsAccessor().getArgument(
                    getConfigKeywordWindow()::get,
                    arguments,
                    context,
                    CommonArguments.KEYWORD_WINDOW_ARG,
                    CommonArguments.KEYWORD_WINDOW_ARG,
                    Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH + "");

            return NumberUtils.toInt(argument.getSafeValue(), Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH);
        }

        @Override
        public String getEntity() {
            return getArgsAccessor().getArgument(
                    null,
                    arguments,
                    context,
                    CommonArguments.ENTITY_NAME_CONTEXT_ARG,
                    CommonArguments.ENTITY_NAME_CONTEXT_ARG,
                    "").getSafeValue();
        }

        public String getPreprocessingHooks() {
            return getArgsAccessor().getArgument(
                    getConfigPreprocessorHooks()::get,
                    arguments,
                    context,
                    CommonArguments.PREPROCESSOR_HOOKS_ARG,
                    CommonArguments.PREPROCESSOR_HOOKS_ARG,
                    "").getSafeValue();
        }

        public String getPreinitializationHooks() {
            return getArgsAccessor().getArgument(
                    getConfigPreinitializationHooks()::get,
                    arguments,
                    context,
                    CommonArguments.PREINITIALIZATION_HOOKS_ARG,
                    CommonArguments.PREINITIALIZATION_HOOKS_ARG,
                    "").getSafeValue();
        }

        public String getPostInferenceHooks() {
            return getArgsAccessor().getArgument(
                    getConfigPostInferenceHooks()::get,
                    arguments,
                    context,
                    CommonArguments.POSTINFERENCE_HOOKS_ARG,
                    CommonArguments.POSTINFERENCE_HOOKS_ARG,
                    "").getSafeValue();
        }
    }
}
