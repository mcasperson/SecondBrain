package secondbrain.domain.handler.impl;

import io.vavr.control.Try;
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
import secondbrain.domain.json.JsonDeserializer;
import secondbrain.domain.resteasy.ProxyCaller;
import secondbrain.domain.toolbuilder.impl.ToolDefinition;
import secondbrain.domain.tools.Tool;
import secondbrain.domain.toolbuilder.ToolBuilder;
import secondbrain.infrastructure.Ollama;
import secondbrain.infrastructure.OllamaGenerateBody;
import org.jboss.resteasy.client.jaxrs.ResteasyClient;
import secondbrain.infrastructure.OllamaResponse;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class PromptHandlerImpl implements PromptHandler {
    @Inject
    private ToolBuilder toolBuilder;

    @Inject
    private ProxyCaller proxyCaller;

    @Inject @Any
    private Instance<Tool> tools;

    @Inject
    private JsonDeserializer jsonDeserializer;

    public String handlePrompt(@NotNull final String prompt) {
        final String llmPrompt = toolBuilder.buildToolPrompt(tools.stream().toList(), prompt);

        final Try<ToolDefinition> toolDefinition = Try.of(() -> proxyCaller.callProxy(
                "http://localhost:11434",
                Ollama.class,
                simple -> simple.getTools(new OllamaGenerateBody("llama3.2", llmPrompt, false))))
                .mapTry(ollama -> jsonDeserializer.deserialize(ollama.response(), ToolDefinition[].class))
                .mapTry(tools -> tools[0]);

        final Try<Optional<Tool>> tool = toolDefinition
                .mapTry(toolDef -> tools.stream().filter(t -> t.getName().equals(toolDef.toolName())).findFirst());

        return tool.mapTry(t -> t.get().call(toolDefinition.get().toolArgs()))
                .getOrElseThrow(() -> new RuntimeException("Failed to call tool"));
    }
}
