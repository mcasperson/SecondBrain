package secondbrain.infrastructure.ollama;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.exceptions.InvalidResponse;
import secondbrain.domain.exceptions.MissingResponse;
import secondbrain.domain.response.ResponseValidation;

import java.util.logging.Logger;

import static io.vavr.control.Try.of;

@ApplicationScoped
public class OllamaClient {
    @Inject
    @ConfigProperty(name = "sb.ollama.url", defaultValue = "http://localhost:11434")
    String uri;

    @Inject
    private ResponseValidation responseValidation;

    @Inject
    private Logger logger;

    public OllamaResponse callOllama(final Client client, final OllamaGenerateBody body) {
        logger.info(body.prompt());
        logger.info("Calling: " + uri);
        logger.info("Called with model: " + body.model());

        return Try.withResources(() -> client.target(uri + "/api/generate")
                        .request()
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .post(Entity.entity(body.sanitizedCopy(), MediaType.APPLICATION_JSON)))
                .of(response -> of(() -> responseValidation.validate(response))
                        .map(r -> r.readEntity(OllamaResponse.class))
                        .recover(InvalidResponse.class, e -> {
                            throw new RuntimeException("Failed to call Ollama:\n"
                                    + response.getStatus());
                        })
                        .recover(MissingResponse.class, e -> {
                            throw new RuntimeException("Failed to call Ollama:\n"
                                    + response.getStatus()
                                    + "\nMake sure to run 'ollama pull " + body.model() + "'"
                                    + "or 'docker exec -it secondbrain-ollama-1 ollama pull " + body.model() + "'");
                        })
                        .get())
                .get();
    }

    public <T> RagMultiDocumentContext<T> callOllama(final Client client, final OllamaGenerateBodyWithContext<T> body) {
        final OllamaResponse response = callOllama(client, new OllamaGenerateBody(body.model(), body.prompt().combinedDocument(), body.stream()));
        return new RagMultiDocumentContext<>(response.response(), body.prompt().individualContexts());
    }

    public <T> RagMultiDocumentContext<T> callOllama(final RagMultiDocumentContext<T> ragDoc, final String model) {
        return Try.withResources(ClientBuilder::newClient)
                .of(client -> callOllama(
                        client,
                        new OllamaGenerateBodyWithContext<>(model, ragDoc, false)))
                .get();
    }
}
