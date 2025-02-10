package secondbrain.domain.tools.publicweb;

import com.google.common.collect.ImmutableList;
import io.vavr.API;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang.math.NumberUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.args.Argument;
import secondbrain.domain.config.ModelConfig;
import secondbrain.domain.constants.Constants;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.context.SentenceSplitter;
import secondbrain.domain.context.SentenceVectorizer;
import secondbrain.domain.exceptions.EmptyString;
import secondbrain.domain.exceptions.ExternalFailure;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.limit.DocumentTrimmer;
import secondbrain.domain.prompt.PromptBuilderSelector;
import secondbrain.domain.reader.FileReader;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.validate.ValidateString;
import secondbrain.infrastructure.ollama.OllamaClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.google.common.base.Predicates.instanceOf;

/**
 * A tool that downloads a public file from HTTP and uses it as the context for a query.
 */
@ApplicationScoped
public class PublicWeb implements Tool<Void> {

    public static final String PUBLICWEB_URL_ARG = "url";
    public static final String PUBLICWEB_DISABLELINKS_ARG = "disableLinks";
    public static final String PUBLICWEB_KEYWORD_ARG = "keywords";
    public static final String PUBLICWEB_KEYWORD_WINDOW_ARG = "keywordWindow";

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
    private ModelConfig modelConfig;

    @Inject
    private FileReader fileReader;

    @Inject
    private SentenceSplitter sentenceSplitter;

    @Inject
    private SentenceVectorizer sentenceVectorizer;

    @Inject
    private PromptBuilderSelector promptBuilderSelector;

    @Inject
    private OllamaClient ollamaClient;

    @Inject
    private ValidateString validateString;

    @Inject
    private DocumentTrimmer documentTrimmer;

    @Inject
    private PublicWebConfig config;

    @Override
    public String getName() {
        return PublicWeb.class.getSimpleName();
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
            final Map<String, String> context,
            final String prompt,
            final List<ToolArgs> arguments) {
        final PublicWebConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, context);

        if (StringUtils.isBlank(parsedArgs.getUrl())) {
            throw new InternalFailure("You must provide a URL to download");
        }

        return Try.of(() -> fileReader.read(parsedArgs.getUrl()))
                .map(content -> documentTrimmer.trimDocumentToKeywords(
                        content,
                        parsedArgs.getKeywords(),
                        parsedArgs.getKeywordWindow()))
                .map(validateString::throwIfEmpty)
                .map(document -> getDocumentContext(document, parsedArgs))
                .map(List::of)
                .recover(ex -> List.of())
                .get();
    }

    @Override
    public RagMultiDocumentContext<Void> call(
            final Map<String, String> context,
            final String prompt,
            final List<ToolArgs> arguments) {

        final List<RagDocumentContext<Void>> contextList = getContext(context, prompt, arguments);

        final PublicWebConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, context);

        if (StringUtils.isBlank(parsedArgs.getUrl())) {
            throw new InternalFailure("You must provide a URL to download");
        }

        final Try<RagMultiDocumentContext<Void>> result = Try.of(() -> contextList)
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

    private RagDocumentContext<Void> getDocumentContext(final String document, final PublicWebConfig.LocalArguments parsedArgs) {
        if (parsedArgs.getDisableLinks()) {
            return new RagDocumentContext<>(getContextLabel(), document, List.of());
        }

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
}

@ApplicationScoped
class PublicWebConfig {

    @Inject
    @ConfigProperty(name = "sb.publicweb.url")
    private Optional<String> url;

    @Inject
    @ConfigProperty(name = "sb.publicweb.disablelinks")
    private Optional<String> disableLinks;

    @Inject
    @ConfigProperty(name = "sb.publicweb.keywords")
    private Optional<String> keywords;

    @Inject
    @ConfigProperty(name = "sb.publicweb.keywordwindow")
    private Optional<String> keywordWindow;

    @Inject
    private ArgsAccessor argsAccessor;

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
            return argsAccessor.getArgument(
                    url::get,
                    arguments,
                    context,
                    PublicWeb.PUBLICWEB_URL_ARG,
                    "publicweb_url",
                    "").value();
        }

        public boolean getDisableLinks() {
            final Argument argument = argsAccessor.getArgument(
                    disableLinks::get,
                    arguments,
                    context,
                    PublicWeb.PUBLICWEB_DISABLELINKS_ARG,
                    "publicweb_disable_links",
                    "");

            return BooleanUtils.toBoolean(argument.value());
        }

        public List<String> getKeywords() {
            return argsAccessor.getArgumentList(
                            keywords::get,
                            arguments,
                            context,
                            PublicWeb.PUBLICWEB_KEYWORD_ARG,
                            "publicweb_keywords",
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
                    PublicWeb.PUBLICWEB_KEYWORD_WINDOW_ARG,
                    "publicweb_keyword_window",
                    Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH + "");

            return NumberUtils.toInt(argument.value(), Constants.DEFAULT_DOCUMENT_TRIMMED_SECTION_LENGTH);
        }
    }
}
