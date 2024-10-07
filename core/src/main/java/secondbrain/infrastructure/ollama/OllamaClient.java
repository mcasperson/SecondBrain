package secondbrain.infrastructure.ollama;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
public class OllamaClient {
    public OllamaResponse getTools(Client client, OllamaGenerateBody body) {
        try (final Response response = client.target("http://localhost:11434/api/generate")
                .request()
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .post(Entity.entity(body, MediaType.APPLICATION_JSON))) {
            return response.readEntity(OllamaResponse.class);
        }
    }
}
