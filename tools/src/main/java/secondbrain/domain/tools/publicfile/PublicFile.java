package secondbrain.domain.tools.publicfile;

import com.google.common.collect.ImmutableList;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang.math.NumberUtils;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.args.Argument;
import secondbrain.domain.config.LocalConfigKeywordsEntity;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.converter.FileToText;
import secondbrain.domain.exceptionhandling.ExceptionMapping;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.processing.DataToRagDoc;
import secondbrain.domain.reader.FileReader;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.tools.publicfile.model.FileContents;
import secondbrain.infrastructure.llm.LlmClient;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A tool that downloads a public file from HTTP or accesses a file from a local disk
 * and uses it as the context for a query.
 */
@ApplicationScoped
public class PublicFile implements Tool<FileContents> {

    public static final String PUBLICWEB_URL_ARG = "url";
    public static final String PUBLICWEB_KEYWORD_ARG = "keywords";
    public static final String PUBLICWEB_KEYWORD_WINDOW_ARG = "keywordWindow";
    public static final String PUBLICWEB_ENTITY_NAME_CONTEXT_ARG = "entityName";

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
                new ToolArguments(PUBLICWEB_KEYWORD_WINDOW_ARG, "The window size around any matching keywords", ""),
                new ToolArguments(
                        PUBLICWEB_KEYWORD_ARG,
                        "The keywords to limit the document to",
                        ""));
    }

    @Override
    public String getContextLabel() {
        return "File Contents";
    }

    @Override
    public List<RagDocumentContext<FileContents>> getContext(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {
        final PublicWebConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        if (StringUtils.isBlank(parsedArgs.getUrl())) {
            throw new InternalFailure("You must provide a URL to download");
        }

        final Try<String> contents = Files.isRegularFile(Paths.get(parsedArgs.getUrl()))
                ? Try.of(() -> fileToText.convert(parsedArgs.getUrl()))
                : Try.of(() -> fileReader.read(parsedArgs.getUrl()));

        return contents
                .map(content -> new FileContents(parsedArgs.getUrl(), parsedArgs.getUrl(), content))
                .map(document -> dataToRagDoc.getDocumentContext(document, getName(), getContextLabel(), parsedArgs))
                .map(List::of)
                .recover(ex -> List.of())
                .get();
    }

    @Override
    public RagMultiDocumentContext<FileContents> call(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {

        final List<RagDocumentContext<FileContents>> contextList = getContext(environmentSettings, prompt, arguments);

        final PublicWebConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        if (StringUtils.isBlank(parsedArgs.getUrl())) {
            throw new InternalFailure("You must provide a URL to download");
        }

        final Try<RagMultiDocumentContext<FileContents>> result = Try.of(() -> contextList)
                .map(ragDoc -> new RagMultiDocumentContext<FileContents>(prompt, INSTRUCTIONS, ragDoc))
                .map(ragDoc -> llmClient.callWithCache(
                        ragDoc,
                        environmentSettings,
                        getName()));

        return exceptionMapping.map(result).get();
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
    @ConfigProperty(name = "sb.publicfile.keywordwindow")
    private Optional<String> configKeywordWindow;

    @Inject
    private ArgsAccessor argsAccessor;

    public Optional<String> getConfigUrl() {
        return configUrl;
    }

    public Optional<String> getConfigKeywords() {
        return configKeywords;
    }

    public Optional<String> getConfigKeywordWindow() {
        return configKeywordWindow;
    }

    public ArgsAccessor getArgsAccessor() {
        return argsAccessor;
    }

    public class LocalArguments implements LocalConfigKeywordsEntity {
        private final List<ToolArgs> arguments;

        private final String prompt;

        private final Map<String, String> context;

        public LocalArguments(final List<ToolArgs> arguments, final String prompt, final Map<String, String> context) {
            this.arguments = arguments;
            this.prompt = prompt;
            this.context = context;
        }

        public String getUrl() {
            return getArgsAccessor().getArgument(
                    getConfigUrl()::get,
                    arguments,
                    context,
                    PublicFile.PUBLICWEB_URL_ARG,
                    PublicFile.PUBLICWEB_URL_ARG,
                    "").value();
        }

        public List<String> getKeywords() {
            return getArgsAccessor().getArgumentList(
                            getConfigKeywords()::get,
                            arguments,
                            context,
                            PublicFile.PUBLICWEB_KEYWORD_ARG,
                            PublicFile.PUBLICWEB_KEYWORD_ARG,
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
                    PublicFile.PUBLICWEB_KEYWORD_WINDOW_ARG,
                    PublicFile.PUBLICWEB_KEYWORD_WINDOW_ARG,
                    Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH + "");

            return NumberUtils.toInt(argument.value(), Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH);
        }

        public String getEntity() {
            return getArgsAccessor().getArgument(
                    null,
                    null,
                    context,
                    null,
                    PublicFile.PUBLICWEB_ENTITY_NAME_CONTEXT_ARG,
                    "").value();
        }
    }
}
