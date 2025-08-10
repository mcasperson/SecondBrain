package secondbrain.infrastructure.azure;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.answer.AnswerFormatter;
import secondbrain.domain.concurrency.SemaphoreLender;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.limit.ListLimiter;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.response.ResponseValidation;
import secondbrain.infrastructure.azure.api.*;
import secondbrain.infrastructure.llm.LlmClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.*;

/**
 * AzureClient provides access to the Azure AI foundry API.
 */
@ApplicationScoped
public class AzureClient implements LlmClient {
    private static final SemaphoreLender SEMAPHORE_LENDER = new SemaphoreLender(1);
    private static final String DEFAULT_MODEL = "Phi-4";

    @Inject
    @ConfigProperty(name = "sb.azurellm.apikey")
    private Optional<String> apiKey;

    @Inject
    @ConfigProperty(name = "sb.azurellm.url")
    private Optional<String> url;

    @Inject
    @ConfigProperty(name = "sb.azurellm.model", defaultValue = "Phi-4")
    private Optional<String> model;

    @Inject
    @ConfigProperty(name = "sb.azurellm.maxOutputTokens", defaultValue = AzureRequest.DEFAULT_OUTPUT_TOKENS + "")
    private Optional<String> outputTokens;

    @Inject
    @ConfigProperty(name = "sb.azurellm.maxInputTokens", defaultValue = AzureRequest.DEFAULT_INPUT_TOKENS + "")
    private Optional<String> inputTokens;

    @Inject
    @ConfigProperty(name = "sb.azurellm.ttldays", defaultValue = "30")
    private String ttlDays;

    @Inject
    private ResponseValidation responseValidation;

    @Inject
    private Instance<AnswerFormatter> answerFormatters;

    @Inject
    private Logger logger;

    @Inject
    private ListLimiter listLimiter;

    @Inject
    private LocalStorage localStorage;

    @Override
    public String call(final String prompt) {
        checkArgument(StringUtils.isNotBlank(prompt));

        return call(prompt, model.orElse(DEFAULT_MODEL));
    }

    @Override
    public String call(final String prompt, final String model) {
        checkArgument(StringUtils.isNotBlank(prompt));
        checkArgument(StringUtils.isNotBlank(model));

        final AzureRequest request = new AzureRequest(
                List.of(new AzureRequestMessage(
                        "user",
                        prompt
                )),
                model
        );

        return call(request);
    }

    @Override
    public <T> RagMultiDocumentContext<T> callWithCache(
            final RagMultiDocumentContext<T> ragDocs,
            final Map<String, String> environmentSettings,
            final String tool) {

        checkNotNull(ragDocs);
        checkNotNull(environmentSettings);
        checkArgument(StringUtils.isNotBlank(tool));

        final int maxOutputTokens = outputTokens
                .map(Integer::parseInt)
                .orElse(AzureRequest.DEFAULT_OUTPUT_TOKENS);

        final int maxInputTokens = inputTokens
                .map(Integer::parseInt)
                .orElse(AzureRequest.DEFAULT_INPUT_TOKENS);

        final int maxChars = maxInputTokens * 4; // Assume 4 chars per token

        final List<AzureRequestMessage> messages = new ArrayList<>();
        messages.add(new AzureRequestMessage("system", ragDocs.instructions()));

        // No individual context can be longer than the maxChars.
        // This ensures we always have at least some of the context available.
        final List<RagDocumentContext<T>> trimmedItems = ragDocs.individualContexts().stream()
                .map(ragDoc -> ragDoc.updateDocument(ragDoc.document().substring(0, Math.min(maxChars, ragDoc.document().length()))))
                .toList();

        // Limit the total size of the context messages to maxChars
        final List<RagDocumentContext<T>> trimmedList = listLimiter.limitListContent(trimmedItems,
                RagDocumentContext::document,
                maxChars);

        messages.addAll(trimmedList.stream()
                .map(ragDoc -> new AzureRequestMessage(
                        "user",
                        ragDoc.contextLabel() + ": " + ragDoc.document()))
                .collect(Collectors.toCollection(ArrayList::new)));

        messages.add(new AzureRequestMessage("user", ragDocs.prompt()));

        final AzureRequest request = new AzureRequest(messages, model.orElse(DEFAULT_MODEL), maxOutputTokens);

        final String promptHash = DigestUtils.sha256Hex(request.generatePromptText() + model + inputTokens + outputTokens);

        final String result = localStorage.getOrPutString(
                tool,
                "AzureLLM",
                promptHash,
                NumberUtils.toInt(ttlDays, 30) * 24 * 60 * 60,
                () -> call(request));

        return ragDocs.updateResponse(result);
    }

    private String call(final AzureRequest request) {
        checkState(apiKey.isPresent());
        checkState(url.isPresent());
        checkState(model.isPresent());

        logger.info("Calling Azure LLM");
        logger.info(request.generatePromptText());

        final String result = Try.withResources(ClientBuilder::newClient)
                .of(client -> Try.withResources(() -> SEMAPHORE_LENDER.lend(client.target(url.get())
                                .request()
                                .header("Content-Type", "application/json")
                                .header("Accept", "application/json")
                                .header("Authorization", "Bearer " + apiKey.get())
                                .post(Entity.entity(request, MediaType.APPLICATION_JSON))))
                        .of(wrapped -> Try.of(wrapped::getWrapped)
                                .map(response -> responseValidation.validate(response, url.get()))
                                .map(response -> response.readEntity(AzureResponse.class))
                                .map(response -> response.getChoices().stream()
                                        .map(AzureResponseChoices::getMessage)
                                        .map(AzureResponseChoicesMessage::getContent)
                                        .reduce("", String::concat))
                                .map(response -> formatResponse(model.get(), response))
                                .onFailure(e -> logger.severe(e.getMessage()))
                                .get()
                        )
                        .get()
                )
                .get();

        logger.info("LLM Response");
        logger.info(result);

        return result;
    }

    private String formatResponse(final String model, final String response) {
        return answerFormatters.stream()
                .filter(b -> Pattern.compile(b.modelRegex()).matcher(model).matches())
                .findFirst()
                .map(formatter -> formatter.formatAnswer(response))
                .orElse(response);
    }
}
