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
import secondbrain.domain.prompt.PromptBuilderSelector;
import secondbrain.domain.response.ResponseValidation;

import java.util.Arrays;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import static io.vavr.control.Try.of;

@ApplicationScoped
public class OllamaClient {
    private static final SemaphoreLender SEMAPHORE_LENDER = new SemaphoreLender(1);

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

    /**
     * Call Ollama with the given body. This method is thread-safe, and only allows one call at a time.
     */
    public synchronized OllamaResponse callOllama(final Client client, final OllamaGenerateBody body) {
        logger.info(body.prompt());
        logger.info("Calling: " + uri);
        logger.info("Called with model: " + body.model());
        logger.info("Called with context window: " + Optional.ofNullable(body.options()).map(OllamaGenerateBodyOptions::num_ctx).map(Object::toString).orElse("null"));

        final String target = uri + "/api/generate";

        return Try.withResources(() -> SEMAPHORE_LENDER.lend(client.target(uri + "/api/generate")
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
                .get();
    }

    public <T> RagMultiDocumentContext<T> callOllama(final Client client, final OllamaGenerateBodyWithContext<T> body) {
        final OllamaResponse response = callOllama(client, new OllamaGenerateBody(
                body.model(),
                body.prompt().combinedDocument(),
                body.stream(),
                new OllamaGenerateBodyOptions(body.contextWindow())));
        return body.prompt().updateDocument(response.response());
    }

    public String callOllamaSimple(final String prompt) {
        return callOllama(new RagMultiDocumentContext<Void>(
                        promptBuilderSelector.getPromptBuilder(modelConfig.getModel())
                                .buildFinalPrompt("", "", prompt)),
                modelConfig.getModel(),
                2048)
                .combinedDocument();
    }

    public <T> RagMultiDocumentContext<T> callOllama(
            final RagMultiDocumentContext<T> ragDoc,
            final String model,
            @Nullable final Integer contextWindow) {
        return Try.withResources(ClientBuilder::newClient)
                .of(client -> callOllama(
                        client,
                        new OllamaGenerateBodyWithContext<>(model, contextWindow, ragDoc, false)))
                .get();
    }

    public <T> RagMultiDocumentContext<T> callOllamaWithCache(
            final RagMultiDocumentContext<T> ragDoc,
            final String model,
            final String tool,
            @Nullable final Integer contextWindow) {
        final String promptHash = DigestUtils.sha256Hex(ragDoc.combinedDocument() + model + contextWindow);

        final String result = localStorage.getOrPutString(
                tool,
                "LLM",
                promptHash,
                NumberUtils.toInt(ttlDays, 30) * 24 * 60 * 60,
                () -> {
            final RagMultiDocumentContext<T> response = callOllama(ragDoc, model, contextWindow);
            final String responseText = response.combinedDocument();
            return CollectionUtils.containsAny(Arrays.asList(ERRORS), responseText.trim()) ? null : responseText;
        });

        return ragDoc.updateDocument(result);
    }

    private String formatResponse(final String model, final String response) {
        return answerFormatters.stream()
                .filter(b -> Pattern.compile(b.modelRegex()).matcher(model).matches())
                .findFirst()
                .map(formatter -> formatter.formatAnswer(response))
                .orElse(response);
    }
}
