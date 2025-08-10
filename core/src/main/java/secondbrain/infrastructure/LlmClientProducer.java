package secondbrain.infrastructure;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.injection.Preferred;
import secondbrain.infrastructure.azure.AzureClient;
import secondbrain.infrastructure.google.GoogleClient;
import secondbrain.infrastructure.llm.LlmClient;
import secondbrain.infrastructure.ollama.OllamaClient;

/**
 * Produces a LlmClient instance based on the configuration.
 */
@ApplicationScoped
public class LlmClientProducer {

    @Inject
    @ConfigProperty(name = "sb.llm.client", defaultValue = "ollama")
    private String client;

    @Produces
    @Preferred
    @ApplicationScoped
    public LlmClient produceLlmClient(final OllamaClient ollamaClient,
                                      final GoogleClient googleClient,
                                      final AzureClient azureClient) {
        if ("google".equalsIgnoreCase(client)) {
            return googleClient;
        }

        if ("azure".equalsIgnoreCase(client)) {
            return azureClient;
        }

        return ollamaClient;
    }
}
