package secondbrain.infrastructure.ollama;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.exceptions.InvalidResponse;
import secondbrain.domain.exceptions.MissingResponse;
import secondbrain.domain.response.ResponseValidation;
import secondbrain.domain.response.impl.OkResponseValidation;

import static io.vavr.control.Try.of;

@ApplicationScoped
public class OllamaClient {
    @Inject
    @ConfigProperty(name = "sb.ollama.url", defaultValue = "http://localhost:11434")
    String uri;

    @Inject
    private ResponseValidation responseValidation;

    public OllamaResponse getTools(@NotNull final Client client, @NotNull final OllamaGenerateBody body) {
        return Try.withResources(() -> client.target(uri + "/api/generate")
                        .request()
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .post(Entity.entity(body, MediaType.APPLICATION_JSON)))
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
}
