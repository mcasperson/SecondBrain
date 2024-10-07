package secondbrain.domain.handler.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotNull;
import secondbrain.domain.handler.PromptHandler;
import secondbrain.domain.json.JsonDeserializer;
import secondbrain.domain.resteasy.ProxyCaller;
import secondbrain.domain.tooldefs.ToolCall;
import secondbrain.domain.tooldefs.ToolDefinition;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.toolbuilder.ToolBuilder;
import secondbrain.infrastructure.Ollama;
import secondbrain.infrastructure.OllamaGenerateBody;
import secondbrain.infrastructure.OllamaResponse;

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

        return Try.of(() -> getToolsPrompt(prompt))
                .map(this::callOllama)
                .map(OllamaResponse::response)
                .mapTry(this::parseResponseAsToolDefinitions)
                .map(tools -> tools[0])
                .map(this::getToolCallFromToolDefinition)
                .map(toolCall -> toolCall
                        .map(ToolCall::call)
                        .orElseGet(() -> "No tool found"))
                .getOrElse("Failed to call tool");
    }

    private String getToolsPrompt(@NotNull final String prompt) {
        return toolBuilder.buildToolPrompt(tools.stream().toList(), prompt);
    }

    private OllamaResponse callOllama(@NotNull final String llmPrompt) {
        return proxyCaller.callProxy(
                "http://localhost:11434",
                Ollama.class,
                simple -> simple.getTools(new OllamaGenerateBody("llama3.2", llmPrompt, false)));
    }

    private ToolDefinition[] parseResponseAsToolDefinitions(@NotNull final String response) throws JsonProcessingException {
        return jsonDeserializer.deserialize(response, ToolDefinition[].class);
    }

    private Optional<ToolCall> getToolCallFromToolDefinition(@NotNull final ToolDefinition toolDefinition) {
        return tools.stream().filter(tool -> tool.getName().equals(toolDefinition.toolName()))
                .findFirst()
                .map(tool -> new ToolCall(tool, toolDefinition));
    }
}
