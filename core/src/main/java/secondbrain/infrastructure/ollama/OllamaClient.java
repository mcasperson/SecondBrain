package secondbrain.infrastructure.ollama;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.answer.AnswerFormatter;
import secondbrain.domain.concurrency.SemaphoreLender;
import secondbrain.domain.config.ModelConfig;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.exceptions.FailedOllama;
import secondbrain.domain.exceptions.InvalidResponse;
import secondbrain.domain.exceptions.MissingResponse;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.prompt.PromptBuilder;
import secondbrain.domain.prompt.PromptBuilderSelector;
import secondbrain.domain.response.ResponseValidation;
import secondbrain.infrastructure.llm.LlmClient;
import secondbrain.infrastructure.ollama.api.OllamaGenerateBody;
import secondbrain.infrastructure.ollama.api.OllamaGenerateBodyOptions;
import secondbrain.infrastructure.ollama.api.OllamaGenerateBodyWithContext;
import secondbrain.infrastructure.ollama.api.OllamaResponse;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static io.vavr.control.Try.of;

@ApplicationScoped
public class OllamaClient implements LlmClient {
    private static final int MAX_RETIES = 3;
    private static final SemaphoreLender SEMAPHORE_LENDER = new SemaphoreLender(1);
    private static final Long RETRY_DELAY = 10000L; // 10 second delay for retries

    /**
     * Common error messages that we don't want to cache.
     */
    private static final String[] ERRORS = {"Predictor is not initialized"};

    @Inject
    @ConfigProperty(name = "sb.ollama.ttldays", defaultValue = "30")
    private String ttlDays;

    @Inject
    @ConfigProperty(name = "sb.ollama.url", defaultValue = "http://localhost:11434")
    private String uri;

    @Inject
    private ResponseValidation responseValidation;

    @Inject
    private Instance<AnswerFormatter> answerFormatters;

    @Inject
    private Logger logger;

    @Inject
    private LocalStorage localStorage;

    @Inject
    private PromptBuilderSelector promptBuilderSelector;

    @Inject
    private ModelConfig modelConfig;

    public synchronized OllamaResponse callOllama(final Client client, final OllamaGenerateBody body) {
        return callOllama(client, body, 0);
    }

    public String call(final String prompt) {
        checkArgument(StringUtils.isNotBlank(prompt));

        return call(prompt, modelConfig.getModel());
    }

    public String call(final String prompt, final String model) {
        checkArgument(StringUtils.isNotBlank(prompt));
        checkArgument(StringUtils.isNotBlank(model));

        return callOllama(new RagMultiDocumentContext<Void>(
                        promptBuilderSelector.getPromptBuilder(modelConfig.getModel())
                                .buildFinalPrompt("", "", prompt)),
                model,
                2048)
                .response();
    }

    public <T> RagMultiDocumentContext<T> callWithCache(
            final RagMultiDocumentContext<T> ragDoc,
            final Map<String, String> environmentSettings,
            final String tool) {
        checkNotNull(ragDoc);
        checkNotNull(environmentSettings);
        checkArgument(StringUtils.isNotBlank(tool));

        final String model = modelConfig.getCalculatedModel(environmentSettings);
        final Integer contextWindow = modelConfig.getCalculatedContextWindow(environmentSettings);

        final String prompt = getPromptFromDocument(ragDoc);

        final String promptHash = DigestUtils.sha256Hex(prompt + model + contextWindow);

        final String result = localStorage.getOrPutString(
                tool,
                "LLM",
                promptHash,
                NumberUtils.toInt(ttlDays, 30) * 24 * 60 * 60,
                () -> {
                    final RagMultiDocumentContext<T> response = callOllama(ragDoc, model, contextWindow);
                    final String responseText = response.response();

                    // Don't cache errors
                    return resultOrDefaultOnError(responseText, null);
                });

        // Don't return cached errors
        return valueOrDefaultOnError(result,
                ragDoc.updateResponse(result),
                ragDoc.updateResponse(callOllama(ragDoc, model, contextWindow).response()));
    }

    /**
     * Call Ollama with the given body. This method is thread-safe, and only allows one call at a time.
     */
    private synchronized OllamaResponse callOllama(final Client client, final OllamaGenerateBody body, int retryCount) {
        if (retryCount > MAX_RETIES) {
            throw new FailedOllama("OllamaClient failed to call Ollama after " + MAX_RETIES + " retries.");
        }

        logger.info(body.prompt());
        logger.fine("Calling: " + uri);
        logger.fine("Called with model: " + body.model());
        logger.fine("Called with context window: " + Optional.ofNullable(body.options()).map(OllamaGenerateBodyOptions::num_ctx).map(Object::toString).orElse("null"));

        final String target = uri + "/api/generate";

        final OllamaResponse result = Try.withResources(() -> SEMAPHORE_LENDER.lend(client.target(uri + "/api/generate")
                        .request()
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .post(Entity.entity(body.sanitizedCopy(), MediaType.APPLICATION_JSON))))
                .of(response -> of(() -> responseValidation.validate(response.getWrapped(), target))
                        .recover(InvalidResponse.class, e -> {
                            throw new FailedOllama("OllamaClient failed to call Ollama:\n"
                                    + e.getCode() + "\n"
                                    + e.getBody(), e);
                        })
                        .recover(MissingResponse.class, e -> {
                            throw new FailedOllama("OllamaClient failed to call Ollama:\n"
                                    + response.getWrapped().getStatus() + "\n"
                                    + Try.of(() -> response.getWrapped().readEntity(String.class)).getOrElse("")
                                    + "\nMake sure to run 'ollama pull " + body.model() + "'"
                                    + "or 'docker exec -it secondbrain-ollama-1 ollama pull " + body.model() + "'");
                        })
                        .map(r -> r.readEntity(OllamaResponse.class))
                        .map(ollamaResponse -> ollamaResponse.replaceResponse(formatResponse(body.model(), ollamaResponse.response())))
                        .get())
                .recover(ex -> {
                    logger.warning("Retrying Ollama call, attempt " + (retryCount + 1));
                    Try.run(() -> Thread.sleep(retryCount * RETRY_DELAY));
                    return callOllama(client, body, retryCount + 1);
                })
                .get();

        logger.info(result.response());

        return result;
    }

    private <T> RagMultiDocumentContext<T> callOllama(final Client client, final OllamaGenerateBodyWithContext<T> body) {
        final String prompt = getPromptFromDocument(body.prompt());

        final OllamaResponse response = callOllama(client, new OllamaGenerateBody(
                body.model(),
                prompt,
                body.stream(),
                new OllamaGenerateBodyOptions(body.contextWindow())));
        return body.prompt().updateResponse(response.response());
    }

    private <T> RagMultiDocumentContext<T> callOllama(
            final RagMultiDocumentContext<T> ragDoc,
            final String model,
            @Nullable final Integer contextWindow) {
        return Try.withResources(ClientBuilder::newClient)
                .of(client -> callOllama(
                        client,
                        new OllamaGenerateBodyWithContext<>(model, contextWindow, ragDoc, false)))
                .get();
    }

    private boolean resultIsError(final String result) {
        return CollectionUtils.containsAny(Arrays.asList(ERRORS), result.trim());
    }

    private <T> T valueOrDefaultOnError(final String result, final T value, final T defaultValue) {
        return resultIsError(result) ? defaultValue : value;
    }

    private String resultOrDefaultOnError(final String result, final String defaultValue) {
        return resultIsError(result) ? defaultValue : result;
    }

    private String formatResponse(final String model, final String response) {
        return answerFormatters.stream()
                .filter(b -> Pattern.compile(b.modelRegex()).matcher(model).matches())
                .findFirst()
                .map(formatter -> formatter.formatAnswer(response))
                .orElse(response);
    }

    private String getPromptFromDocument(final RagMultiDocumentContext<?> ragDoc) {
        final PromptBuilder promptBuilder = promptBuilderSelector.getPromptBuilder(modelConfig.getModel());

        final String promptContent = ragDoc.individualContexts()
                .stream()
                .map(context -> promptBuilder.buildContextPrompt(context.contextLabel(), context.document()))
                .collect(Collectors.joining("\n"));

        return promptBuilder.buildFinalPrompt(
                ragDoc.instructions(),
                promptContent,
                ragDoc.prompt());
    }
}
