package secondbrain.infrastructure.google;

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
import secondbrain.domain.constants.Constants;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.exceptions.Timeout;
import secondbrain.domain.httpclient.TimeoutHttpClientCaller;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.infrastructure.google.api.*;
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
 * GoogleClient provides access to the Google AI studio API.
 */
@ApplicationScoped
public class GoogleClient implements LlmClient {
    private static final RateLimiter RATE_LIMITER = RateLimiter.create(Constants.DEFAULT_RATE_LIMIT_PER_SECOND);
    private static final String DEFAULT_MODEL = "gemini-2.0-flash";
    private static final int DEFAULT_CACHE_TTL_DAYS = 90;
    private static final long API_CONNECTION_TIMEOUT_SECONDS_DEFAULT = 10;
    private static final long API_CALL_TIMEOUT_SECONDS_DEFAULT = 60 * 10; // I've seen "Time to last byte" take at least 8 minutes, so we need a large buffer.
    private static final long API_CALL_DELAY_SECONDS_DEFAULT = 30;
    private static final long CLIENT_TIMEOUT_BUFFER_SECONDS = 5;
    private static final int API_RETRIES = 3;
    private static final String API_CALL_TIMEOUT_MESSAGE = "Call timed out after " + API_CALL_TIMEOUT_SECONDS_DEFAULT + " seconds";

    @Inject
    @ConfigProperty(name = "sb.googlellm.apikey")
    private Optional<String> apiKey;

    @Inject
    @ConfigProperty(name = "sb.googlellm.url", defaultValue = "https://generativelanguage.googleapis.com/v1beta/models/")
    private Optional<String> url;

    @Inject
    @ConfigProperty(name = "sb.googlellm.model", defaultValue = DEFAULT_MODEL)
    private Optional<String> model;

    @Inject
    @ConfigProperty(name = "sb.googlellm.ttldDays", defaultValue = "30")
    private String ttlDays;

    @Inject
    @ConfigProperty(name = "sb.googlellm.timeOutSeconds", defaultValue = API_CALL_TIMEOUT_SECONDS_DEFAULT + "")
    private Long timeout;

    @Inject
    @ConfigProperty(name = "sb.googlellm.retryCount", defaultValue = API_RETRIES + "")
    private Integer retryCount;

    @Inject
    @ConfigProperty(name = "sb.googlellm.retryDelaySeconds", defaultValue = API_CALL_DELAY_SECONDS_DEFAULT + "")
    private Long retryDelay;

    @Inject
    private Logger logger;

    @Inject
    @Preferred
    private LocalStorage localStorage;

    @Inject
    private TimeoutHttpClientCaller httpClientCaller;

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

        final GoogleRequest request = new GoogleRequest(
                List.of(new GoogleRequestContents(
                        List.of(new GoogleRequestContentsParts(prompt))
                ))
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

        final List<GoogleRequestContentsParts> parts = ragDocs.individualContexts().stream()
                .map(ragDoc -> new GoogleRequestContentsParts(ragDoc.contextLabel() + ": " + ragDoc.document()))
                .collect(Collectors.toCollection(ArrayList::new));

        parts.add(new GoogleRequestContentsParts(ragDocs.prompt()));

        final GoogleRequest request = new GoogleRequest(
                List.of(new GoogleRequestContents(parts)
                ),
                new GoogleRequestSystemInstruction(
                        List.of(
                                new GoogleRequestContentsParts(ragDocs.instructions())
                        )
                )
        );

        final String promptHash = DigestUtils.sha256Hex(request.generatePromptText() + model);

        final String result = localStorage.getOrPutString(
                        tool,
                        "GoogleLLM",
                        promptHash,
                        NumberUtils.toInt(ttlDays, DEFAULT_CACHE_TTL_DAYS) * 24 * 60 * 60,
                        () -> call(request))
                .result();
        return ragDocs.updateResponse(result);
    }

    private String call(final GoogleRequest request) {
        checkState(apiKey.isPresent());
        checkState(url.isPresent());
        checkState(model.isPresent());

        logger.fine("Calling Google LLM");
        logger.info(request.generatePromptText());

        RATE_LIMITER.acquire();

        final String result = httpClientCaller.call(
                this::getClient,
                client -> client.target(url.get() + model.get() + ":generateContent")
                        .request()
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .header("X-goog-api-key", apiKey.get())
                        .post(Entity.entity(request, MediaType.APPLICATION_JSON)),
                response -> Try.of(() -> response.readEntity(GoogleResponse.class))
                        .map(this::isError)
                        .map(r -> r.getCandidates().stream()
                                .map(GoogleResponseCandidates::getContent)
                                .flatMap(content -> content.getParts().stream())
                                .map(GoogleResponseCandidatesContentParts::getText)
                                .reduce("", String::concat))
                        .onFailure(e -> logger.severe(e.getMessage()))
                        .get(),
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

    private GoogleResponse isError(final GoogleResponse response) {
        if (response == null) {
            throw new IllegalStateException("Google LLM response is empty or invalid.");
        }

        if (response.error() != null) {
            final List<String> errorMessage = new ArrayList<>();
            errorMessage.add("Google LLM Error: " + response.error().message());
            if (response.error().code() != null) {
                errorMessage.add("(Code: " + response.error().code() + ")");
            }
            throw new IllegalStateException(String.join(" ", errorMessage));
        }

        return response;
    }
}
