package secondbrain.infrastructure.azure;

import com.google.common.util.concurrent.RateLimiter;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.answer.AnswerFormatterService;
import secondbrain.domain.concurrency.SemaphoreLender;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.exceptions.Timeout;
import secondbrain.domain.httpclient.ResponseCallback;
import secondbrain.domain.httpclient.TimeoutHttpClientCaller;
import secondbrain.domain.limit.ListLimiter;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.response.ResponseValidation;
import secondbrain.infrastructure.azure.api.*;
import secondbrain.infrastructure.llm.LlmClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.*;

/**
 * AzureClient provides access to the Azure AI foundry API.
 */
@ApplicationScoped
public class AzureClient implements LlmClient {
    private static final SemaphoreLender SEMAPHORE_LENDER = new SemaphoreLender(1);
    private static final String DEFAULT_MODEL = "Phi-4";
    private static final long API_CONNECTION_TIMEOUT_SECONDS_DEFAULT = 10;
    private static final long API_CALL_TIMEOUT_SECONDS_DEFAULT = 60 * 2; // 2 minutes
    private static final long API_CALL_DELAY_SECONDS_DEFAULT = 30;
    private static final long CLIENT_TIMEOUT_BUFFER_SECONDS = 5;
    private static final int API_RETRIES = 3;
    private static final String API_CALL_TIMEOUT_MESSAGE = "Call timed out after " + API_CALL_TIMEOUT_SECONDS_DEFAULT + " seconds";

    // Default rate is around 250 requests per minute. 2 requests per second keeps us well under that.
    private static final RateLimiter RATE_LIMITER = RateLimiter.create(2);

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
    @ConfigProperty(name = "sb.azurellm.maxOutputTokens", defaultValue = AzureRequestMaxCompletionTokens.DEFAULT_OUTPUT_TOKENS + "")
    private Optional<String> outputTokens;

    @Inject
    @ConfigProperty(name = "sb.azurellm.maxInputTokens", defaultValue = AzureRequestMaxCompletionTokens.DEFAULT_INPUT_TOKENS + "")
    private Optional<String> inputTokens;

    @Inject
    @ConfigProperty(name = "sb.azurellm.ttlDays", defaultValue = "30")
    private String ttlDays;

    @Inject
    @ConfigProperty(name = "sb.azurellm.timeOutSeconds", defaultValue = API_CALL_TIMEOUT_SECONDS_DEFAULT + "")
    private Long timeout;

    @Inject
    @ConfigProperty(name = "sb.azurellm.retryCount", defaultValue = API_RETRIES + "")
    private Integer retryCount;

    @Inject
    @ConfigProperty(name = "sb.azurellm.retryDelaySeconds", defaultValue = API_CALL_DELAY_SECONDS_DEFAULT + "")
    private Long retryDelay;

    @Inject
    private TimeoutHttpClientCaller httpClientCaller;

    @Inject
    private ResponseValidation responseValidation;

    @Inject
    private AnswerFormatterService answerFormatterService;

    @Inject
    private Logger logger;

    @Inject
    private ListLimiter listLimiter;

    @Inject
    private LocalStorage localStorage;

    private Client getClient() {
        final ClientBuilder clientBuilder = ClientBuilder.newBuilder();
        clientBuilder.connectTimeout(API_CONNECTION_TIMEOUT_SECONDS_DEFAULT, TimeUnit.SECONDS);
        // We want to use the timeoutService to handle timeouts, so we set the client timeout slightly longer.
        clientBuilder.readTimeout(API_CALL_TIMEOUT_SECONDS_DEFAULT + CLIENT_TIMEOUT_BUFFER_SECONDS, TimeUnit.SECONDS);
        return clientBuilder.build();
    }

    @Override
    public String call(final String prompt) {
        checkArgument(StringUtils.isNotBlank(prompt));

        return call(prompt, model.orElse(DEFAULT_MODEL));
    }

    @Override
    public String call(final String prompt, final String model) {
        checkArgument(StringUtils.isNotBlank(prompt));
        checkArgument(StringUtils.isNotBlank(model));

        final PromptTextGenerator request = new AzureRequestMaxCompletionTokens(
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
                .orElse(AzureRequestMaxCompletionTokens.DEFAULT_OUTPUT_TOKENS);

        final int maxInputTokens = inputTokens
                .map(Integer::parseInt)
                .orElse(AzureRequestMaxCompletionTokens.DEFAULT_INPUT_TOKENS);

        final int maxChars = (int) (maxInputTokens * AzureRequestMaxCompletionTokens.DEFAULT_CHARS_PER_INPUT_TOKENS);

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

        final PromptTextGenerator request = new AzureRequestMaxCompletionTokens(messages, maxOutputTokens, model.orElse(DEFAULT_MODEL));

        final String promptHash = DigestUtils.sha256Hex(request.generatePromptText() + model + inputTokens + outputTokens);

        final String result = localStorage.getOrPutString(
                tool,
                "AzureLLM",
                promptHash,
                NumberUtils.toInt(ttlDays, 30) * 24 * 60 * 60,
                () -> call(request));

        return ragDocs.updateResponse(result);
    }

    private String call(final PromptTextGenerator request) {
        checkState(apiKey.isPresent());
        checkState(url.isPresent());
        checkState(model.isPresent());

        RATE_LIMITER.acquire();

        logger.info("Calling Azure LLM");
        logger.info(request.generatePromptText());

        final String result = httpClientCaller.call(
                this::getClient,
                client -> client.target(url.get())
                        .request()
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .header("Authorization", "Bearer " + apiKey.get())
                        .post(Entity.entity(request, MediaType.APPLICATION_JSON)),
                new ResponseCallback() {
                    @Override
                    public String handleResponse(Response response) {
                        return Try.of(() -> responseValidation.validate(response, url.get()))
                                .map(r -> r.readEntity(AzureResponse.class))
                                .map(r -> r.getChoices().stream()
                                        .map(AzureResponseChoices::getMessage)
                                        .map(AzureResponseChoicesMessage::getContent)
                                        .reduce("", String::concat))
                                .map(r -> answerFormatterService.formatResponse(model.get(), r))
                                .onFailure(e -> logger.severe(e.getMessage()))
                                .get();
                    }
                },
                e -> new RuntimeException("Failed to get comments from ZenDesk API", e),
                () -> {
                    throw new Timeout(API_CALL_TIMEOUT_MESSAGE);
                },
                API_CALL_TIMEOUT_SECONDS_DEFAULT,
                retryDelay,
                retryCount);

        logger.info("LLM Response");
        logger.info(result);

        return result;
    }
}
