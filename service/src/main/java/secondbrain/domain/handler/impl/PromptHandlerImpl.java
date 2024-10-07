package secondbrain.domain.handler.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.client.ClientBuilder;
import secondbrain.domain.handler.PromptHandler;
import secondbrain.domain.json.JsonDeserializer;
import secondbrain.domain.tooldefs.ToolCall;
import secondbrain.domain.tooldefs.ToolDefinition;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.toolbuilder.ToolBuilder;
import secondbrain.domain.tooldefs.ToolDefinitionFallback;
import secondbrain.infrastructure.ollama.OllamaClient;
import secondbrain.infrastructure.ollama.OllamaGenerateBody;
import secondbrain.infrastructure.ollama.OllamaResponse;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class PromptHandlerImpl implements PromptHandler {
    @Inject
    private ToolBuilder toolBuilder;

    @Inject @Any
    private Instance<Tool> tools;

    @Inject
    private OllamaClient ollamaClient;

    @Inject
    private JsonDeserializer jsonDeserializer;

    public String handlePrompt(@NotNull final Map<String, String> context, @NotNull final String prompt) {

        return Try.of(() -> getToolsPrompt(prompt))
                .map(this::callOllama)
                .map(OllamaResponse::response)
                .mapTry(this::parseResponseAsToolDefinitions)
                .map(tools -> tools[0])
                .map(this::getToolCallFromToolDefinition)
                .map(toolCall -> toolCall
                        .map(tool -> tool.call(context, prompt))
                        .orElseGet(() -> "No tool found"))
                .recover(Throwable.class, e -> "Failed to call tool " + e.getMessage())
                .get();
    }

    private String getToolsPrompt(@NotNull final String prompt) {
        return toolBuilder.buildToolPrompt(tools.stream().toList(), prompt);
    }

    private OllamaResponse callOllama(@NotNull final String llmPrompt) {
        return ollamaClient.getTools(
                ClientBuilder.newClient(),
                new OllamaGenerateBody("llama3.2", llmPrompt, false));
    }

    private ToolDefinition[] parseResponseAsToolDefinitions(@NotNull final String response) throws JsonProcessingException {
        return Try.of(() -> jsonDeserializer.deserialize(response, ToolDefinition[].class))
                .recoverWith(error -> Try.of(() -> parseResponseAsToolDefinitionsFallback(response)))
                .get();
    }

    private ToolDefinition[] parseResponseAsToolDefinitionsFallback(@NotNull final String response) throws JsonProcessingException {
        final ToolDefinitionFallback[] tool = jsonDeserializer.deserialize(response, ToolDefinitionFallback[].class);
        return Arrays.stream(tool).map(t -> new ToolDefinition(t.toolName(), List.of())).toArray(ToolDefinition[]::new);
    }

    private Optional<ToolCall> getToolCallFromToolDefinition(@NotNull final ToolDefinition toolDefinition) {
        return tools.stream().filter(tool -> tool.getName().equals(toolDefinition.toolName()))
                .findFirst()
                .map(tool -> new ToolCall(tool, toolDefinition));
    }
}
