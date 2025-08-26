package secondbrain.domain.tools.rating;

import io.smallrye.common.annotation.Identifier;
import io.vavr.API;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.tika.utils.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.exceptions.EmptyString;
import secondbrain.domain.exceptions.ExternalFailure;
import secondbrain.domain.exceptions.FailedOllama;
import secondbrain.domain.exceptions.InternalFailure;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.sanitize.SanitizeDocument;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;
import secondbrain.domain.validate.ValidateList;
import secondbrain.infrastructure.llm.LlmClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.base.Predicates.instanceOf;

/**
 * RatingTool rates a document or context against the supplied question or criteria and returns a score
 * from 1 to 10. This is useful to filter out context that is not relevant to the question.
 */
@ApplicationScoped
public class RatingTool implements Tool<Void> {
    public static final String RATING_DOCUMENT_CONTEXT_ARG = "rating_document";
    public static final String RATING_SECOND_MODEL_ARG = "secondModel";

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
    @Preferred
    private LlmClient llmClient;

    @Inject
    private ValidateList validateList;

    @Inject
    @Identifier("findFirstMarkdownBlock")
    private SanitizeDocument findFirstMarkdownBlock;

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

        return List.of(new RagDocumentContext<>(getName(), getContextLabel(), parsedArgs.getDocument(), List.of()));
    }

    @Override
    public RagMultiDocumentContext<Void> call(final Map<String, String> environmentSettings, final String prompt, final List<ToolArgs> arguments) {
        final RatingConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);

        final Try<RagMultiDocumentContext<Void>> result = Try.of(() -> getContext(environmentSettings, prompt, arguments))
                .map(validateList::throwIfEmpty)
                .map(ragDoc -> new RagMultiDocumentContext<Void>(prompt, INSTRUCTIONS, ragDoc))
                .map(ragDoc -> llmClient.callWithCache(ragDoc, environmentSettings, getName()))
                /*
                 We expect a single value, but might get some whitespace from a thinking model that had the
                 thinking response removed.
                 */
                .map(ragDoc -> ragDoc.updateResponse(findFirstMarkdownBlock.sanitize(ragDoc.getResponse()).trim()));

        final Try<RagMultiDocumentContext<Void>> firstResultWithMappedFailures = mapFailures(result);

        final Try<RagMultiDocumentContext<Void>> secondResult = callSecondary(environmentSettings, prompt, arguments, parsedArgs);

        if (secondResult == null) {
            return firstResultWithMappedFailures.get();
        }

        // The final result is the average of the two results from the two models
        final Try<RagMultiDocumentContext<Void>> secondResultWithMappedFailures = mapFailures(secondResult);

        final int firstResultInt = Integer.parseInt(firstResultWithMappedFailures.get().getResponse());
        final int secondResultInt = Integer.parseInt(secondResultWithMappedFailures.get().getResponse());

        return firstResultWithMappedFailures.get().updateResponse((firstResultInt + secondResultInt) / 2 + "");
    }

    private Try<RagMultiDocumentContext<Void>> mapFailures(final Try<RagMultiDocumentContext<Void>> result) {
        // Handle mapFailure in isolation to avoid intellij making a mess of the formatting
        // https://github.com/vavr-io/vavr/issues/2411
        return result.mapFailure(
                API.Case(API.$(instanceOf(EmptyString.class)), throwable -> new InternalFailure("The document was empty")),
                API.Case(API.$(instanceOf(InternalFailure.class)), throwable -> throwable),
                API.Case(API.$(instanceOf(FailedOllama.class)), throwable -> new InternalFailure(throwable.getMessage(), throwable)),
                API.Case(API.$(), ex -> new ExternalFailure(getName() + " failed to call Ollama", ex)));
    }

    private Try<RagMultiDocumentContext<Void>> callSecondary(final Map<String, String> environmentSettings, final String prompt, final List<ToolArgs> arguments, final RatingConfig.LocalArguments parsedArgs) {
        if (StringUtils.isBlank(parsedArgs.getSecondModel())) {
            return null;
        }

        final Map<String, String> newEnvironmentSettings = new HashMap<>(environmentSettings);
        newEnvironmentSettings.put(LlmClient.MODEL_OVERRIDE_ENV, parsedArgs.getSecondModel());

        return Try.of(() -> getContext(newEnvironmentSettings, prompt, arguments))
                .map(validateList::throwIfEmpty)
                .map(ragDoc -> new RagMultiDocumentContext<Void>(prompt, INSTRUCTIONS, ragDoc))
                .map(ragDoc -> llmClient.callWithCache(ragDoc, environmentSettings, getName()))
                /*
                 We expect a single value, but might get some whitespace from a thinking model that had the
                 thinking response removed.
                 */
                .map(ragDoc -> ragDoc.updateResponse(findFirstMarkdownBlock.sanitize(ragDoc.getResponse()).trim()));
    }

    @Override
    public String getContextLabel() {
        return "Document";
    }
}

@ApplicationScoped
class RatingConfig {
    @Inject
    private ArgsAccessor argsAccessor;
    @Inject
    @ConfigProperty(name = "sb.rating.secondModel", defaultValue = "")
    private Optional<String> configSecondModel;

    public ArgsAccessor getArgsAccessor() {
        return argsAccessor;
    }

    /**
     * You can optionally have a second model to do a rating, with the result being the average of the two.
     */
    public Optional<String> getConfigSecondModel() {
        return configSecondModel;
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

        public String getSecondModel() {
            return getArgsAccessor().getArgument(
                    getConfigSecondModel()::get,
                    arguments,
                    environmentSettings,
                    RatingTool.RATING_SECOND_MODEL_ARG,
                    RatingTool.RATING_SECOND_MODEL_ARG,
                    "").value();
        }
    }
}
