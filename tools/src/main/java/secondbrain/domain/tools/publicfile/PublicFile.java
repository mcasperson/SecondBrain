package secondbrain.domain.tools.publicfile;

import com.google.common.collect.ImmutableList;
import io.vavr.API;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang.math.NumberUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.args.Argument;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.context.SentenceSplitter;
import secondbrain.domain.context.SentenceVectorizer;
import secondbrain.domain.converter.FileToText;
import secondbrain.domain.exceptions.EmptyString;
import secondbrain.domain.exceptions.ExternalFailure;
import secondbrain.domain.exceptions.FailedOllama;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.limit.DocumentTrimmer;
import secondbrain.domain.limit.TrimResult;
import secondbrain.domain.reader.FileReader;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.validate.ValidateString;
import secondbrain.infrastructure.llm.LlmClient;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.base.Predicates.instanceOf;

/**
 * A tool that downloads a public file from HTTP or accesses a file from a local disk
 * and uses it as the context for a query.
 */
@ApplicationScoped
public class PublicFile implements Tool<Void> {

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
    private FileReader fileReader;

    @Inject
    private SentenceSplitter sentenceSplitter;

    @Inject
    private SentenceVectorizer sentenceVectorizer;

    @Inject
    @Preferred
    private LlmClient llmClient;

    @Inject
    private ValidateString validateString;

    @Inject
    private DocumentTrimmer documentTrimmer;

    @Inject
    private PublicWebConfig config;

    @Inject
    private FileToText fileToText;

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
    public List<RagDocumentContext<Void>> getContext(
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
                .map(content -> documentTrimmer.trimDocumentToKeywords(
                        content,
                        parsedArgs.getKeywords(),
                        parsedArgs.getKeywordWindow()))
                .map(trimResult -> validateString.throwIfEmpty(trimResult, TrimResult::document))
                .map(document -> getDocumentContext(document, parsedArgs))
                .map(List::of)
                .recover(ex -> List.of())
                .get();
    }

    @Override
    public RagMultiDocumentContext<Void> call(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {

        final List<RagDocumentContext<Void>> contextList = getContext(environmentSettings, prompt, arguments);

        final PublicWebConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        if (StringUtils.isBlank(parsedArgs.getUrl())) {
            throw new InternalFailure("You must provide a URL to download");
        }

        final Try<RagMultiDocumentContext<Void>> result = Try.of(() -> contextList)
                .map(ragDoc -> new RagMultiDocumentContext<Void>(prompt, INSTRUCTIONS, ragDoc))
                .map(ragDoc -> llmClient.callWithCache(
                        ragDoc,
                        environmentSettings,
                        getName()));

        // Handle mapFailure in isolation to avoid intellij making a mess of the formatting
        // https://github.com/vavr-io/vavr/issues/2411
        return result.mapFailure(
                        API.Case(API.$(instanceOf(EmptyString.class)), throwable -> new InternalFailure("The document was empty")),
                        API.Case(API.$(instanceOf(InternalFailure.class)), throwable -> throwable),
                        API.Case(API.$(instanceOf(FailedOllama.class)), throwable -> new InternalFailure(throwable.getMessage(), throwable)),
                        API.Case(API.$(), ex -> new ExternalFailure(getName() + " failed to call Ollama", ex)))
                .get();
    }

    private RagDocumentContext<Void> getDocumentContext(final TrimResult trimResult, final PublicWebConfig.LocalArguments parsedArgs) {
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

    public class LocalArguments {
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
