package secondbrain.infrastructure.azure;

import com.google.common.util.concurrent.RateLimiter;
import io.smallrye.common.annotation.Identifier;
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
import secondbrain.domain.exceptions.*;
import secondbrain.domain.httpclient.TimeoutHttpClientCaller;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.json.JsonDeserializerJackson;
import secondbrain.domain.limit.ListLimiter;
import secondbrain.domain.list.StringToList;
import secondbrain.domain.mutex.Mutex;
import secondbrain.domain.persist.CacheResult;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.response.ResponseInspector;
import secondbrain.domain.response.ResponseValidation;
import secondbrain.domain.validate.ValidateString;
import secondbrain.infrastructure.azure.api.AzureRequestMaxCompletionTokens;
import secondbrain.infrastructure.azure.api.AzureRequestMessage;
import secondbrain.infrastructure.azure.api.AzureResponse;
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
    /**
     * We need to trim the input to ensure the LLM can handle it.
     * This value is an assumption about the size of the generated output.
     */
    private static final int DEFAULT_OUTPUT_TOKENS = 2048;
    /**
     * This value is an assumption on the size of the input, which is the maximum context window minus the output tokens.
     * This is based on a model with a 16k context window, which is common for models like Phi-4.
     */
    private static final int DEFAULT_INPUT_TOKENS = 16384 - DEFAULT_OUTPUT_TOKENS;
    /**
     * This is an assumption about the number of characters per input token.
     */
    private static final float DEFAULT_CHARS_PER_INPUT_TOKENS = 3.5f;
    /**
     * This is the default model.
     */
    private static final String DEFAULT_MODEL = "Phi-4";
    private static final int DEFAULT_CACHE_TTL_DAYS = 90;
    private static final long API_CONNECTION_TIMEOUT_SECONDS_DEFAULT = 10;
    private static final long API_CALL_TIMEOUT_SECONDS_DEFAULT = 60 * 10; // I've seen "Time to last byte" take at least 8 minutes, so we need a large buffer.
    private static final long TIMEOUT_API_CALL_DELAY_SECONDS_DEFAULT = 30;
    private static final float TIME_IF_TOO_LONG_FRACTION = 0.6f;
    private static final long CLIENT_TIMEOUT_BUFFER_SECONDS = 5;
    private static final int TIMEOUT_API_RETRIES = 3;
    private static final int RATELIMIT_API_RETRIES = 3;
    private static final long RATELIMIT_API_CALL_DELAY_SECONDS_DEFAULT = 90;
    private static final String API_CALL_TIMEOUT_MESSAGE = "Call timed out after " + API_CALL_TIMEOUT_SECONDS_DEFAULT + " seconds";
    private static final long MUTEX_TIMEOUT_MS = 30 * 60 * 1000; // 30 minutes

    // Default rate is around 250 requests per minute.
    private static final RateLimiter RATE_LIMITER = RateLimiter.create(4);

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
    @ConfigProperty(name = "sb.azurellm.maxOutputTokens", defaultValue = "")
    private Optional<String> outputTokens;

    @Inject
    @ConfigProperty(name = "sb.azurellm.maxInputTokens", defaultValue = DEFAULT_INPUT_TOKENS + "")
    private Optional<String> inputTokens;

    @Inject
    @ConfigProperty(name = "sb.azurellm.ttlDays", defaultValue = "30")
    private String ttlDays;

    @Inject
    @ConfigProperty(name = "sb.azurellm.timeOutSeconds", defaultValue = API_CALL_TIMEOUT_SECONDS_DEFAULT + "")
    private Long timeout;

    @Inject
    @ConfigProperty(name = "sb.azurellm.trimIfTooLongFraction", defaultValue = TIME_IF_TOO_LONG_FRACTION + "")
    private Float trimIfTooLongFraction;

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
    @ConfigProperty(name = "sb.azurellm.lock", defaultValue = "sb_azure.lock")
    private String lockFile;

    @Inject
    @Preferred
    private Mutex mutex;

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
    @Preferred
    private LocalStorage localStorage;

    @Inject
    private ValidateString validateString;

    @Inject
    @Identifier("MessageTooLongResponseInspector")
    private ResponseInspector messageTooLongResponseInspector;

    @Inject
    private StringToList stringToList;

    @Inject
    private JsonDeserializerJackson jsonDeserializerJackson;

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

        final AzureRequestMaxCompletionTokens request = new AzureRequestMaxCompletionTokens(
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

        final Integer maxOutputTokens = outputTokens
                .map(t -> Try.of(() -> Integer.parseInt(t)).getOrNull())
                .orElse(null);

        final Integer maxInputTokens = inputTokens
                .map(t -> Try.of(() -> Integer.parseInt(t)).getOrNull())
                .orElse(DEFAULT_INPUT_TOKENS);

        final String modelName = environmentSettings.getOrDefault(MODEL_OVERRIDE_ENV, this.model.orElse(DEFAULT_MODEL));
        final Integer modelContextWindow = Try.of(() -> Integer.parseInt(environmentSettings.getOrDefault(CONTEXT_WINDOW_OVERRIDE_ENV, maxInputTokens + "")))
                .getOrElse(maxInputTokens);

        final int maxChars = (int) (modelContextWindow * DEFAULT_CHARS_PER_INPUT_TOKENS);

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

        final AzureRequestMaxCompletionTokens request = new AzureRequestMaxCompletionTokens(messages, maxOutputTokens, modelName);

        final String promptHash = DigestUtils.sha256Hex(request.generatePromptText() + modelName + inputTokens.orElse("") + url.orElse(""));

        logger.fine("Calling Azure LLM");
        logger.fine(request.generatePromptText());

        final CacheResult<String> result = handleCaching(request, tool, promptHash);

        logger.info("LLM Response from " + modelName + (result.fromCache() ? " (from cache)" : ""));
        logger.info(result.result());

        return ragDocs.updateResponse(result.result());
    }

    private CacheResult<String> handleCaching(final AzureRequestMaxCompletionTokens request, final String tool, final String promptHash) {
        final int ttl = NumberUtils.toInt(ttlDays, DEFAULT_CACHE_TTL_DAYS) * 24 * 60 * 60;
        final String cacheSource = "AzureLLMV3";

        // Bypass cache altogether if both read and write are disabled.
        if (getDisableToolReadCache().contains(tool) && getDisableToolWriteCache().contains(tool)) {
            return new CacheResult<String>(call(request), false);
        }

        // We can refresh the cache with a new value, but we don't want to read from it.
        if (getDisableToolReadCache().contains(tool)) {
            final String result = call(request);
            localStorage.putString(
                    tool,
                    cacheSource,
                    promptHash,
                    ttl,
                    result);
            return new CacheResult<String>(result, false);
        }

        // We can get a cached value but not save it
        if (getDisableToolWriteCache().contains(tool)) {
            return Try.of(() -> localStorage.getString(
                            tool,
                            cacheSource,
                            promptHash))
                    .getOrElse(() -> new CacheResult<String>(call(request), false));
        }

        // Normal caching operation - get or put
        return localStorage.getOrPutString(
                tool,
                cacheSource,
                promptHash,
                ttl,
                () -> call(request));
    }

    private String call(final AzureRequestMaxCompletionTokens request) {
        checkState(apiKey.isPresent(), "Azure LLM API Key is not configured. Please set sb.azurellm.apikey");
        checkState(url.isPresent(), "Azure LLM URL is not configured. Please set sb.azurellm.url");
        checkState(model.isPresent(), "Azure LLM model is not configured. Please set sb.azurellm.model");

        RATE_LIMITER.acquire();

        return mutex.acquire(MUTEX_TIMEOUT_MS, lockFile, () -> callLocked(request, 0));
    }

    private String callLocked(final AzureRequestMaxCompletionTokens request, int retry) {
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
                                .peek(r -> logger.fine(jsonDeserializerJackson.serialize(r)))
                                .map(AzureResponse::getResponseText)
                                .map(validateString::throwIfBlank)
                                .map(r -> answerFormatterService.formatResponse(model.get(), r))
                                .onFailure(e -> logger.severe(e.getMessage()))
                                .get(),
                        e -> new FailedAzure("Failed to call the Azure AI service", e),
                        () -> {
                            throw new Timeout(API_CALL_TIMEOUT_MESSAGE);
                        },
                        API_CALL_TIMEOUT_SECONDS_DEFAULT,
                        retryDelay,
                        retryCount))
                .recover(FailedAzure.class, ex -> {

                    if (ex.getCause() instanceof InvalidResponse invalidResponse) {
                        if (invalidResponse.getCode() == 429 || invalidResponse.getCode() >= 500) {
                            Try.run(() -> Thread.sleep(RATELIMIT_API_CALL_DELAY_SECONDS_DEFAULT * 1000));
                            return callLocked(request, retry + 1);
                        }

                        if (invalidResponse.getCode() == 400 && messageTooLongResponseInspector.isMatch(invalidResponse.getBody())) {
                            final AzureRequestMaxCompletionTokens trimmed = request.updateMessages(
                                    listLimiter.limitListContentByFraction(
                                            request.getMessages(),
                                            AzureRequestMessage::content,
                                            trimIfTooLongFraction));

                            return callLocked(trimmed, retry + 1);
                        }
                    }

                    if (ex.getCause() instanceof EmptyString) {
                        return callLocked(request, retry + 1);
                    }

                    throw ex;
                })
                .get();
    }

    private List<String> getDisableToolReadCache() {
        final String fixedString = disableToolReadCache.map(String::trim).orElse("");
        return stringToList.convert(fixedString);
    }

    private List<String> getDisableToolWriteCache() {
        final String fixedString = disableToolWriteCache.map(String::trim).orElse("");
        return stringToList.convert(fixedString);
    }
}
