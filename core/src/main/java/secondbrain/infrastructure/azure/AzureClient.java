package secondbrain.infrastructure.azure;

import com.google.common.util.concurrent.RateLimiter;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.answer.AnswerFormatterService;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.exceptions.InvalidResponse;
import secondbrain.domain.exceptions.RateLimit;
import secondbrain.domain.exceptions.Timeout;
import secondbrain.domain.httpclient.TimeoutHttpClientCaller;
import secondbrain.domain.limit.ListLimiter;
import secondbrain.domain.persist.CacheResult;
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
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.*;

/**
 * AzureClient provides access to the Azure AI foundry API.
 */
@ApplicationScoped
public class AzureClient implements LlmClient {
    private static final String DEFAULT_MODEL = "Phi-4";
    private static final int DEFAULT_CACHE_TTL_DAYS = 90;
    private static final long API_CONNECTION_TIMEOUT_SECONDS_DEFAULT = 10;
    private static final long API_CALL_TIMEOUT_SECONDS_DEFAULT = 60 * 10; // I've seen "Time to last byte" take at least 8 minutes, so we need a large buffer.
    private static final long TIMEOUT_API_CALL_DELAY_SECONDS_DEFAULT = 30;
    private static final long CLIENT_TIMEOUT_BUFFER_SECONDS = 5;
    private static final int TIMEOUT_API_RETRIES = 3;
    private static final int RATELIMIT_API_RETRIES = 3;
    private static final long RATELIMIT_API_CALL_DELAY_SECONDS_DEFAULT = 60;
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
    @ConfigProperty(name = "sb.azurellm.retryCount", defaultValue = TIMEOUT_API_RETRIES + "")
    private Integer retryCount;

    @Inject
    @ConfigProperty(name = "sb.azurellm.retryDelaySeconds", defaultValue = TIMEOUT_API_CALL_DELAY_SECONDS_DEFAULT + "")
    private Long retryDelay;

    /**
     * This property is used to selectively disable reading values from the cache for a tool. The value is a comma
     * separated list of tool names.
     */
    @Inject
    @ConfigProperty(name = "sb.azurellm.disableToolReadCache", defaultValue = "")
    private Optional<String> disableToolReadCache;

    /**
     * This property is used to selectively disable writing values from the cache for a tool. The value is a comma
     * separated list of tool names.
     */
    @Inject
    @ConfigProperty(name = "sb.azurellm.disableToolWriteCache", defaultValue = "")
    private Optional<String> disableToolWriteCache;

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
                .map(t -> Try.of(() -> Integer.parseInt(t)).getOrNull())
                .orElse(AzureRequestMaxCompletionTokens.DEFAULT_OUTPUT_TOKENS);

        final int maxInputTokens = inputTokens
                .map(t -> Try.of(() -> Integer.parseInt(t)).getOrNull())
                .orElse(AzureRequestMaxCompletionTokens.DEFAULT_INPUT_TOKENS);

        final String modelName = environmentSettings.getOrDefault(MODEL_OVERRIDE_ENV, this.model.orElse(DEFAULT_MODEL));
        final Integer modelContextWindow = Try.of(() -> Integer.parseInt(environmentSettings.getOrDefault(CONTEXT_WINDOW_OVERRIDE_ENV, maxInputTokens + "")))
                .getOrElse(maxInputTokens);

        final int maxChars = (int) (modelContextWindow * AzureRequestMaxCompletionTokens.DEFAULT_CHARS_PER_INPUT_TOKENS);

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

        final PromptTextGenerator request = new AzureRequestMaxCompletionTokens(messages, maxOutputTokens, modelName);

        final String promptHash = DigestUtils.sha256Hex(request.generatePromptText() + modelName + inputTokens + outputTokens);

        logger.info("Calling Azure LLM");
        logger.info(request.generatePromptText());

        final CacheResult<String> result = handleCaching(request, tool, promptHash);

        logger.info("LLM Response from " + modelName + (result.fromCache() ? " (from cache)" : ""));
        logger.info(result.result());

        return ragDocs.updateResponse(result.result());
    }

    private CacheResult<String> handleCaching(final PromptTextGenerator request, final String tool, final String promptHash) {
        final int ttl = NumberUtils.toInt(ttlDays, DEFAULT_CACHE_TTL_DAYS) * 24 * 60 * 60;

        // Bypass cache altogether if both read and write are disabled.
        if (getDisableToolReadCache().contains(tool) && getDisableToolWriteCache().contains(tool)) {
            return new CacheResult<String>(call(request), false);
        }

        // We can refresh the cache with a new value, but we don't want to read from it.
        if (getDisableToolReadCache().contains(tool)) {
            final String result = call(request);
            localStorage.putString(
                    tool,
                    "AzureLLM",
                    promptHash,
                    ttl,
                    result);
            return new CacheResult<String>(result, false);
        }

        // We can get a cached value but not save it
        if (getDisableToolWriteCache().contains(tool)) {
            return Try.of(() -> localStorage.getString(
                            tool,
                            "AzureLLM",
                            promptHash))
                    .getOrElse(() -> new CacheResult<String>(call(request), false));
        }

        // Normal caching operation - get or put
        return localStorage.getOrPutString(
                tool,
                "AzureLLM",
                promptHash,
                ttl,
                () -> call(request));
    }

    private String call(final PromptTextGenerator request) {
        checkState(apiKey.isPresent());
        checkState(url.isPresent());
        checkState(model.isPresent());

        RATE_LIMITER.acquire();

        final String result = call(request, 0);

        return result;
    }

    private String call(final PromptTextGenerator request, int retry) {
        if (retry > RATELIMIT_API_RETRIES) {
            throw new RateLimit("Exceeded max retries for rate limited Azure LLM calls");
        }

        return Try.of(() -> httpClientCaller.call(
                        this::getClient,
                        client -> client.target(url.get())
                                .request()
                                .header("Content-Type", "application/json")
                                .header("Accept", "application/json")
                                .header("Authorization", "Bearer " + apiKey.get())
                                .post(Entity.entity(request, MediaType.APPLICATION_JSON)),
                        response -> Try.of(() -> responseValidation.validate(response, url.get()))
                                .map(r -> r.readEntity(AzureResponse.class))
                                .map(r -> r.getChoices().stream()
                                        .map(AzureResponseOutput::getMessage)
                                        .map(AzureResponseOutputMessage::getContent)
                                        .reduce("", String::concat))
                                .map(r -> answerFormatterService.formatResponse(model.get(), r))
                                .onFailure(e -> logger.severe(e.getMessage()))
                                .get(),
                        e -> new RuntimeException("Failed to call the Azure AI service", e),
                        () -> {
                            throw new Timeout(API_CALL_TIMEOUT_MESSAGE);
                        },
                        API_CALL_TIMEOUT_SECONDS_DEFAULT,
                        retryDelay,
                        retryCount))
                .recover(InvalidResponse.class, ex -> {
                    if (ex.getCode() == 429) {
                        Try.run(() -> Thread.sleep(RATELIMIT_API_CALL_DELAY_SECONDS_DEFAULT * 1000));
                        return call(request, retry + 1);
                    }

                    throw ex;
                })
                .get();
    }

    private List<String> getDisableToolReadCache() {
        final String fixedString = disableToolReadCache.map(String::trim).orElse("");

        return Stream.of(fixedString.split(","))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toList());
    }

    private List<String> getDisableToolWriteCache() {
        final String fixedString = disableToolWriteCache.map(String::trim).orElse("");

        return Stream.of(fixedString.split(","))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toList());
    }
}
