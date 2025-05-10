package secondbrain.domain.tools.rating;

import io.vavr.API;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.config.ModelConfig;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.exceptions.EmptyString;
import secondbrain.domain.exceptions.ExternalFailure;
import secondbrain.domain.exceptions.FailedOllama;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.prompt.PromptBuilderSelector;
import secondbrain.domain.tooldefs.MetaObjectResult;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.validate.ValidateString;
import secondbrain.infrastructure.ollama.OllamaClient;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.google.common.base.Predicates.instanceOf;

/**
 * RatingTool rates a document or context against the supplied question or criteria and returns a score
 * from 1 to 10. This is useful to filter out context that is not relevant to the question.
 */
@ApplicationScoped
public class RatingTool implements Tool<Void> {
    public static final String RATING_DOCUMENT_CONTEXT_ARG = "rating_document";

    private static final String INSTRUCTIONS = """
            You are a helpful assistant.
            You are given a question and the contents of a document related to the question.
            You must rate the document on a scale of 1 to 10 based on how well it answers the question.
            The response must be a single number between 1 and 10.
            You will be penalized for returning any text in the response.
            """.stripLeading();

    @Inject
    private RatingConfig config;

    @Inject
    private PromptBuilderSelector promptBuilderSelector;

    @Inject
    private ModelConfig modelConfig;

    @Inject
    private OllamaClient ollamaClient;

    @Inject
    private ValidateString validateString;

    @Override
    public String getName() {
        return RatingTool.class.getSimpleName();
    }

    @Override
    public String getDescription() {
        return "Rates a document or context against the supplied question or criteria and returns a score from 1 to 10";
    }

    @Override
    public List<ToolArguments> getArguments() {
        return List.of();
    }

    @Override
    public List<RagDocumentContext<Void>> getContext(final Map<String, String> environmentSettings, final String prompt, final List<ToolArgs> arguments) {
        final RatingConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        return List.of(new RagDocumentContext<>(getContextLabel(), parsedArgs.getDocument(), List.of()));
    }

    @Override
    public List<MetaObjectResult> getMetadata(Map<String, String> environmentSettings, String prompt, List<ToolArgs> arguments) {
        return List.of();
    }

    @Override
    public RagMultiDocumentContext<Void> call(final Map<String, String> environmentSettings, final String prompt, final List<ToolArgs> arguments) {
        final Try<RagMultiDocumentContext<Void>> result = Try.of(() -> getContext(environmentSettings, prompt, arguments))
                .map(ragDoc -> mergeContext(ragDoc, modelConfig.getCalculatedModel(environmentSettings)))
                .map(ragContext -> ragContext.updateDocument(promptBuilderSelector
                        .getPromptBuilder(modelConfig.getCalculatedModel(environmentSettings))
                        .buildFinalPrompt(
                                INSTRUCTIONS,
                                ragContext.getDocumentRight(modelConfig.getCalculatedContextWindowChars(environmentSettings)),
                                prompt)))
                .map(ragDoc -> validateString.throwIfEmpty(ragDoc, RagMultiDocumentContext::combinedDocument))
                .map(ragDoc -> ollamaClient.callOllamaWithCache(
                        ragDoc,
                        modelConfig.getCalculatedModel(environmentSettings),
                        getName(),
                        modelConfig.getCalculatedContextWindow(environmentSettings)))
                /*
                 We expect a single value, but might get some whitespace from a thinking model that had the
                 thinking response removed.
                 */
                .map(ragDoc -> ragDoc.updateDocument(ragDoc.getCombinedDocument().trim()));

        // Handle mapFailure in isolation to avoid intellij making a mess of the formatting
        // https://github.com/vavr-io/vavr/issues/2411
        return result.mapFailure(
                        API.Case(API.$(instanceOf(EmptyString.class)), throwable -> new InternalFailure("The document was empty")),
                        API.Case(API.$(instanceOf(InternalFailure.class)), throwable -> throwable),
                        API.Case(API.$(instanceOf(FailedOllama.class)), throwable -> new InternalFailure(throwable.getMessage(), throwable)),
                        API.Case(API.$(), ex -> new ExternalFailure(getName() + " failed to call Ollama", ex)))
                .get();
    }

    @Override
    public String getContextLabel() {
        return "Document";
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
}

@ApplicationScoped
class RatingConfig {
    @Inject
    private ArgsAccessor argsAccessor;

    public ArgsAccessor getArgsAccessor() {
        return argsAccessor;
    }

    public class LocalArguments {
        private final List<ToolArgs> arguments;

        private final String prompt;

        private final Map<String, String> environmentSettings;

        public LocalArguments(final List<ToolArgs> arguments, final String prompt, final Map<String, String> environmentSettings) {
            this.arguments = arguments;
            this.prompt = prompt;
            this.environmentSettings = environmentSettings;
        }

        public String getDocument() {
            return getArgsAccessor().getArgument(
                    null,
                    null,
                    environmentSettings,
                    null,
                    RatingTool.RATING_DOCUMENT_CONTEXT_ARG,
                    "").value();
        }
    }
}
