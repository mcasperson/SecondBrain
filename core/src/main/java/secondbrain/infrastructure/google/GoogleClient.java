package secondbrain.infrastructure.google;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.concurrency.SemaphoreLender;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.infrastructure.google.api.*;
import secondbrain.infrastructure.llm.LlmClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * GoogleClient provides access to the Google AI studio API.
 */
@ApplicationScoped
public class GoogleClient implements LlmClient {
    private static final SemaphoreLender SEMAPHORE_LENDER = new SemaphoreLender(1);
    private static final String DEFAULT_MODEL = "gemini-2.0-flash";

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
    private Logger logger;

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

        return ragDocs.updateResponse(call(
                        new GoogleRequest(
                                List.of(new GoogleRequestContents(parts)
                                ),
                                new GoogleRequestSystemInstruction(
                                        List.of(
                                                new GoogleRequestContentsParts(ragDocs.instructions())
                                        )
                                )
                        )
                )
        );
    }

    private String call(final GoogleRequest request) {
        if (apiKey.isEmpty()) {
            throw new IllegalStateException("Google API key is not configured.");
        }

        if (url.isEmpty()) {
            throw new IllegalStateException("Google URL is not configured.");
        }

        if (model.isEmpty()) {
            throw new IllegalStateException("Google model is not configured.");
        }

        logger.info("Calling Google LLM");
        logger.info(request.generatePromptText());

        final String result = Try.withResources(ClientBuilder::newClient)
                .of(client -> Try.withResources(() -> SEMAPHORE_LENDER.lend(client.target(url.get() + model.get() + ":generateContent")
                                .request()
                                .header("Content-Type", "application/json")
                                .header("Accept", "application/json")
                                .header("X-goog-api-key", apiKey.get())
                                .post(Entity.entity(request, MediaType.APPLICATION_JSON))))
                        .of(wrapped -> Try.of(wrapped::getWrapped)
                                .map(response -> response.readEntity(GoogleResponse.class))
                                .map(this::isError)
                                .map(response -> response.getCandidates().stream()
                                        .map(GoogleResponseCandidates::getContent)
                                        .flatMap(content -> content.getParts().stream())
                                        .map(GoogleResponseCandidatesContentParts::getText)
                                        .reduce("", String::concat))
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
