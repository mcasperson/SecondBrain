package secondbrain.domain.handler.impl;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.WebTarget;
import org.jboss.resteasy.client.jaxrs.ResteasyWebTarget;
import secondbrain.domain.handler.PromptHandler;
import secondbrain.domain.resteasy.ProxyCaller;
import secondbrain.domain.tools.Tool;
import secondbrain.domain.toolbuilder.ToolBuilder;
import secondbrain.infrastructure.Ollama;
import secondbrain.infrastructure.OllamaGenerateBody;
import org.jboss.resteasy.client.jaxrs.ResteasyClient;
import secondbrain.infrastructure.OllamaResponse;

@ApplicationScoped
public class PromptHandlerImpl implements PromptHandler {
    @Inject
    private ToolBuilder toolBuilder;

    @Inject
    private ProxyCaller proxyCaller;

    @Inject @Any
    private Instance<Tool> tools;

    public String handlePrompt(@NotNull final String prompt) {
        final String llmPrompt = toolBuilder.buildToolPrompt(tools.stream().toList(), prompt);

        final OllamaResponse ollama = proxyCaller.callProxy(
                "http://localhost:11434",
                Ollama.class,
                simple -> simple.getTools(new OllamaGenerateBody("llama3.2", llmPrompt, false)));

        return ollama.response();
    }
}
