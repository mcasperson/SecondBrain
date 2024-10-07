package secondbrain.infrastructure.ollama;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
public class OllamaClient {
    public OllamaResponse getTools(@NotNull final Client client, @NotNull final OllamaGenerateBody body) {
        try (final Response response = client.target("http://localhost:11434/api/generate")
                .request()
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .post(Entity.entity(body, MediaType.APPLICATION_JSON))) {

            if (response.getStatus() == 404) {
                throw new RuntimeException("Failed to call Ollama:\n" + response.getStatus() + "\nMake sure to run 'ollama pull llama3.2'");
            }

            if (response.getStatus() != 200) {
                throw new RuntimeException("Failed to call Ollama:\n" + response.getStatus());
            }

            return response.readEntity(OllamaResponse.class);
        }
    }
}
