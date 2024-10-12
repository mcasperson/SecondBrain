package secondbrain.infrastructure.ollama;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class OllamaClient {
    @Inject
    @ConfigProperty(name = "sb.ollama.url", defaultValue = "http://localhost:11434")
    String uri;

    public OllamaResponse getTools(@NotNull final Client client, @NotNull final OllamaGenerateBody body) {
        try (final Response response = client.target(uri + "/api/generate")
                .request()
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .post(Entity.entity(body, MediaType.APPLICATION_JSON))) {

            if (response.getStatus() == 404) {
                throw new RuntimeException("Failed to call Ollama:\n"
                        + response.getStatus()
                        + "\nMake sure to run 'ollama pull " + body.model() + "'"
                        + "\nor 'docker exec -it secondbrain-ollama-1 ollama run " + body.model() + "'");
            }

            if (response.getStatus() != 200) {
                throw new RuntimeException("Failed to call Ollama:\n" + response.getStatus());
            }

            return response.readEntity(OllamaResponse.class);
        }
    }
}
