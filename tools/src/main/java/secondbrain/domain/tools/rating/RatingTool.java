package secondbrain.domain.tools.rating;

import io.smallrye.common.annotation.Identifier;
import io.vavr.API;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.tika.utils.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.args.ArgsAccessor;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.exceptions.*;
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
import java.util.logging.Logger;
import java.util.stream.Stream;

import static com.google.common.base.Predicates.instanceOf;

/**
 * RatingTool rates a document or context against the supplied question or criteria and returns a score
 * from 1 to 10. This is useful to filter out context that is not relevant to the question.
 */
@ApplicationScoped
public class RatingTool implements Tool<Void> {
    public static final String RATING_DOCUMENT_CONTEXT_ARG = "rating_document";
    public static final String RATING_SECOND_MODEL_ARG = "secondModel";
    public static final String IGNORE_INVALID_RESPONSES_ARG = "ignoreinvalidResponses";

    private static final String INSTRUCTIONS = """
            You are a helpful assistant.
            You are given a question and the contents of a document related to the question.
            You must rate the document on a scale of 0 to 10 based on how well it answers the question.
            The response must be a single number between 0 and 10.
            You will be penalized for returning any additional text or markup in the response.
            """.stripLeading();

    @Inject
    private RatingConfig config;

    @Inject
    @Preferred
    private LlmClient llmClient;

    @Inject
    private ValidateList validateList;

    @Inject
    private Logger logger;

    @Inject
    @Identifier("findFirstMarkdownBlock")
    private SanitizeDocument findFirstMarkdownBlock;

    @Inject
    @Identifier("getFirstDigits")
    private SanitizeDocument getFirstDigits;

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

        final Try<RagMultiDocumentContext<Void>> result = callLLM(environmentSettings, prompt, arguments, parsedArgs);

        if (StringUtils.isBlank(parsedArgs.getSecondModel())) {
            final int resultInt = Integer.parseInt(result.get().getResponse());
            if (resultInt < 0 || resultInt > 10) {
                throw new InvalidAnswer("The rating response was invalid: " + resultInt);
            }
            return result.get().updateResponse(resultInt + "");
        }

        final Map<String, String> newEnvironmentSettings = new HashMap<>(environmentSettings);
        newEnvironmentSettings.put(LlmClient.MODEL_OVERRIDE_ENV, parsedArgs.getSecondModel());
        final Try<RagMultiDocumentContext<Void>> secondResult = callLLM(newEnvironmentSettings, prompt, arguments, parsedArgs);

        // We use multiple models to catch cases where a single model returns inaccurate responses.
        // For example, I have seen chatgpt-5-mini return a rating of 10 for content that is clearly not a 10.
        // We also need to account for the case where other models return invalid responses, such as a text
        // description rather than a number. I have seen this a lot with Phi-4.
        // If we have a secondary model and we are ignoring invalid responses, then we simply filter out
        // any invalid responses and take the average of the valid ones.
        final List<Integer> results = Stream.of(
                        Try.of(() -> Integer.parseInt(result.get().getResponse()))
                                .recover(ex -> -1)
                                .get(),
                        Try.of(() -> Integer.parseInt(secondResult.get().getResponse()))
                                .recover(ex -> -1)
                                .get()
                )
                .toList();

        if (!parsedArgs.ignoreInvalidResponses()) {
            final List<String> invalidResponses = results.stream().filter(i -> i < 0 || i > 10).map(Object::toString).toList();
            if (!invalidResponses.isEmpty()) {
                throw new InvalidAnswer("The following responses were invalid: " + String.join(", ", invalidResponses));
            }
        }

        final List<Integer> filteredResults = results.stream()
                .filter(i -> i >= 0 && i <= 10)
                .toList();

        if (filteredResults.isEmpty()) {
            throw new InvalidAnswer("Both models returned invalid responses");
        }

        final int average = (int) filteredResults.stream()
                .mapToInt(Integer::intValue)
                .average()
                .orElse(0.0);

        logger.info("RatingTool: Values = " + String.join(",", results.stream().map(Object::toString).toList()) + ", Average = " + average);

        return result.get().updateResponse(average + "");
    }

    private Try<RagMultiDocumentContext<Void>> callLLM(final Map<String, String> environmentSettings, final String prompt, final List<ToolArgs> arguments, final RatingConfig.LocalArguments parsedArgs) {
        final Try<RagMultiDocumentContext<Void>> result = Try.of(() -> getContext(environmentSettings, prompt, arguments))
                .map(validateList::throwIfEmpty)
                .map(ragDoc -> new RagMultiDocumentContext<Void>(prompt, INSTRUCTIONS, ragDoc))
                .map(ragDoc -> llmClient.callWithCache(ragDoc, environmentSettings, getName()))
                /*
                 We expect a single value, but might get some whitespace from a thinking model that had the
                 thinking response removed.
                 */
                .map(ragDoc -> ragDoc.updateResponse(
                        getFirstDigits.sanitize(
                                findFirstMarkdownBlock.sanitize(ragDoc.getResponse()).trim())));

        return result.mapFailure(
                API.Case(API.$(instanceOf(EmptyString.class)), throwable -> new InternalFailure("The document was empty")),
                API.Case(API.$(instanceOf(InternalFailure.class)), throwable -> throwable),
                API.Case(API.$(instanceOf(FailedOllama.class)), throwable -> new InternalFailure(throwable.getMessage(), throwable)),
                API.Case(API.$(), ex -> new ExternalFailure(getName() + " failed to call Ollama", ex)));
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

    /**
     * Set to true to ignore invalid responses from either the primary or secondary model.
     * If both models return invalid responses, an exception will be thrown.
     * This setting is useful when using a small secondary model as a check against the primary model,
     * but the secondary model is known to return invalid responses occasionally.
     */
    @Inject
    @ConfigProperty(name = "sb.rating.ignoreInvalidResponses", defaultValue = "false")
    private Optional<String> configIgnoreInvalidResponses;

    public ArgsAccessor getArgsAccessor() {
        return argsAccessor;
    }

    /**
     * You can optionally have a second model to do a rating, with the result being the average of the two.
     */
    public Optional<String> getConfigSecondModel() {
        return configSecondModel;
    }

    public Optional<String> getConfigIgnoreInvalidResponses() {
        return configIgnoreInvalidResponses;
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

        public boolean ignoreInvalidResponses() {
            final String value = getArgsAccessor().getArgument(
                    getConfigIgnoreInvalidResponses()::get,
                    arguments,
                    environmentSettings,
                    RatingTool.IGNORE_INVALID_RESPONSES_ARG,
                    RatingTool.IGNORE_INVALID_RESPONSES_ARG,
                    "").value();

            return BooleanUtils.toBoolean(value);
        }
    }
}
