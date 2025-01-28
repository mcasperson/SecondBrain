package secondbrain.infrastructure.ollama;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import org.apache.commons.codec.digest.DigestUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.exceptions.FailedOllama;
import secondbrain.domain.exceptions.InvalidResponse;
import secondbrain.domain.exceptions.MissingResponse;
import secondbrain.domain.json.JsonDeserializer;
import secondbrain.domain.persist.LocalStorage;
import secondbrain.domain.response.ResponseValidation;

import java.util.Optional;
import java.util.logging.Logger;

import static io.vavr.control.Try.of;

@ApplicationScoped
public class OllamaClient {
    @Inject
    @ConfigProperty(name = "sb.ollama.url", defaultValue = "http://localhost:11434")
    private String uri;

    @Inject
    private ResponseValidation responseValidation;

    @Inject
    private Logger logger;

    @Inject
    private LocalStorage localStorage;

    @Inject
    private JsonDeserializer jsonDeserializer;

    public OllamaResponse callOllama(final Client client, final OllamaGenerateBody body) {
        logger.info(body.prompt());
        logger.info("Calling: " + uri);
        logger.info("Called with model: " + body.model());
        logger.info("Called with context window: " + Optional.ofNullable(body.options()).map(OllamaGenerateBodyOptions::num_ctx).map(Object::toString).orElse("null"));

        return Try.withResources(() -> client.target(uri + "/api/generate")
                        .request()
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .post(Entity.entity(body.sanitizedCopy(), MediaType.APPLICATION_JSON)))
                .of(response -> of(() -> responseValidation.validate(response))
                        .recover(InvalidResponse.class, e -> {
                            throw new FailedOllama("OllamaClient failed to call Ollama:\n"
                                    + e.getCode() + "\n"
                                    + e.getBody(), e);
                        })
                        .recover(MissingResponse.class, e -> {
                            throw new FailedOllama("OllamaClient failed to call Ollama:\n"
                                    + response.getStatus() + "\n"
                                    + Try.of(() -> response.readEntity(String.class)).getOrElse("")
                                    + "\nMake sure to run 'ollama pull " + body.model() + "'"
                                    + "or 'docker exec -it secondbrain-ollama-1 ollama pull " + body.model() + "'");
                        })
                        .map(r -> r.readEntity(OllamaResponse.class))
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

    public <T> RagMultiDocumentContext<T> callOllama(final RagMultiDocumentContext<T> ragDoc, final String model, @Nullable final Integer contextWindow) {
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

        final String result = localStorage.getOrPutString(tool, "LLM", promptHash, () -> {
            final RagMultiDocumentContext<T> response = callOllama(ragDoc, model, contextWindow);
            return response.combinedDocument();
        });

        return ragDoc.updateDocument(result);
    }
}
