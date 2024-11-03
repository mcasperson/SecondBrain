package secondbrain.domain.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.vavr.control.Try;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.ClientBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.json.JsonDeserializer;
import secondbrain.domain.toolbuilder.ToolBuilder;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolCall;
import secondbrain.domain.tooldefs.ToolDefinition;
import secondbrain.domain.tooldefs.ToolDefinitionFallback;
import secondbrain.domain.validate.ValidateList;
import secondbrain.infrastructure.ollama.OllamaClient;
import secondbrain.infrastructure.ollama.OllamaGenerateBody;
import secondbrain.infrastructure.ollama.OllamaResponse;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Implementation of the prompt handler.
 */
@ApplicationScoped
public class PromptHandlerOllama implements PromptHandler {
    private static final int TOOL_RETRY = 5;

    @Inject
    @ConfigProperty(name = "sb.ollama.model", defaultValue = "llama3.2")
    String model;

    @Inject
    private ToolBuilder toolBuilder;

    @Inject
    @Any
    private Instance<Tool> tools;

    @Inject
    private OllamaClient ollamaClient;

    @Inject
    private JsonDeserializer jsonDeserializer;

    @Inject
    private ValidateList validateList;


    public String handlePrompt(final Map<String, String> context, final String prompt) {

        return Try.of(() -> getToolsPrompt(prompt))
                .map(toolPrompt -> selectOllamaTool(toolPrompt, 1))
                .map(this::getToolCallFromToolDefinition)
                .map(toolCall -> callTool(toolCall.orElse(null), context, prompt))
                .recover(ProcessingException.class, e -> "Failed to connect to Ollama. You must install Ollama from https://ollama.com/download: " + e.toString())
                .recover(Throwable.class, e -> "Failed to find a tool or call it: " + e.toString())
                .get();
    }

    /**
     * The LLM will sometimes return invalid JSON for tool selection, so we retry a few times
     *
     * @param toolPrompt The tool prompt
     * @param count      The retry count
     * @return The selected tool
     */
    private ToolDefinition selectOllamaTool(final String toolPrompt, int count) {
        return Try.of(() -> callOllama(toolPrompt))
                .map(OllamaResponse::response)
                .mapTry(this::parseResponseAsToolDefinitions)
                .mapTry(validateList::throwIfEmpty)
                .mapTry(List::getFirst)
                .recoverWith(error -> Try.of(() -> {
                    if (count < TOOL_RETRY) {
                        return selectOllamaTool(toolPrompt, count + 1);
                    }
                    throw error;
                }))
                .get();
    }

    private String callTool(@Nullable final ToolCall toolCall, final Map<String, String> context, final String prompt) {
        if (toolCall == null) {
            return "No tool found";
        }

        return toolCall.call(context, prompt);
    }

    private String getToolsPrompt(final String prompt) {
        return toolBuilder.buildToolPrompt(tools.stream().toList(), prompt);
    }

    private OllamaResponse callOllama(final String llmPrompt) {
        return Try.withResources(ClientBuilder::newClient)
                .of(client ->
                        ollamaClient.getTools(
                                client,
                                new OllamaGenerateBody(model, llmPrompt, false)))
                .get();
    }

    private List<ToolDefinition> parseResponseAsToolDefinitions(final String response) throws JsonProcessingException {
        return Try.of(() -> jsonDeserializer.deserialize(response, ToolDefinition[].class))
                .map(List::of)
                .recoverWith(error -> Try.of(() -> parseResponseAsToolDefinitionsFallback(response)))
                .get();
    }

    private List<ToolDefinition> parseResponseAsToolDefinitionsFallback(final String response) throws JsonProcessingException {
        final ToolDefinitionFallback[] tool = jsonDeserializer.deserialize(response, ToolDefinitionFallback[].class);
        return Arrays.stream(tool).map(t -> new ToolDefinition(t.toolName(), List.of())).toList();
    }

    private Optional<ToolCall> getToolCallFromToolDefinition(final ToolDefinition toolDefinition) {
        return tools.stream().filter(tool -> tool.getName().equals(toolDefinition.toolName()))
                .findFirst()
                .map(tool -> new ToolCall(tool, toolDefinition));
    }
}
