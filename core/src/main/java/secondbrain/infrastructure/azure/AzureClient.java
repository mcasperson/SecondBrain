package secondbrain.infrastructure.azure;

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
import secondbrain.infrastructure.azure.api.*;
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

        final List<AzureRequestMessage> messages = new ArrayList<>();
        messages.add(new AzureRequestMessage("system", ragDocs.instructions()));

        messages.addAll(ragDocs.individualContexts().stream()
                .map(ragDoc -> new AzureRequestMessage(
                        "user",
                        ragDoc.contextLabel() + ": " + ragDoc.document()))
                .collect(Collectors.toCollection(ArrayList::new)));

        messages.add(new AzureRequestMessage("user", ragDocs.prompt()));

        return ragDocs.updateResponse(call(new AzureRequest(messages, model.orElse(DEFAULT_MODEL))));
    }

    private String call(final AzureRequest request) {
        if (apiKey.isEmpty()) {
            throw new IllegalStateException("Azure API key is not configured.");
        }

        if (url.isEmpty()) {
            throw new IllegalStateException("Azure URL is not configured.");
        }

        logger.info("Calling Azure LLM");
        logger.info(request.generatePromptText());

        final String result = Try.withResources(ClientBuilder::newClient)
                .of(client -> Try.withResources(() -> SEMAPHORE_LENDER.lend(client.target(url.get())
                                .request()
                                .header("Content-Type", "application/json")
                                .header("Accept", "application/json")
                                .header("X-goog-api-key", apiKey.get())
                                .post(Entity.entity(request, MediaType.APPLICATION_JSON))))
                        .of(wrapped -> Try.of(wrapped::getWrapped)
                                .map(response -> response.readEntity(AzureResponse.class))
                                .map(response -> response.getChoices().stream()
                                        .map(AzureResponseChoices::getMessage)
                                        .map(AzureResponseChoicesMessage::getContent)
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
}
