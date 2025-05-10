package secondbrain.domain.toolbuilder;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientBuilder;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.json.JsonDeserializer;
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
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

@ApplicationScoped
public class ToolSelector {
    /**
     * The model to select a tool can be specifically defined. This allows tool calling
     * to use one model, and the tool itself to use another. Only llama3 variants are supported.
     */
    @Inject
    @ConfigProperty(name = "sb.ollama.toolmodel", defaultValue = "llama3.2")
    private Optional<String> toolModel;

    @Inject
    @ConfigProperty(name = "sb.tools.force")
    private Optional<String> force;

    @Inject
    @ConfigProperty(name = "sb.ollama.timeout", defaultValue = "10")
    private Optional<String> timeout;

    @Inject
    @Any
    private Instance<Tool<?>> tools;

    @Inject
    private OllamaClient ollamaClient;

    @Inject
    private JsonDeserializer jsonDeserializer;

    @Inject
    private ValidateList validateList;

    @Inject
    private ToolBuilder toolBuilder;

    @Inject
    private Logger logger;

    public ToolCall getTool(final String prompt, final Map<String, String> context) {
        /*
            When forcing the selection of a tool, all arguments must be supplied
            through the context or via injected values because they can not be
            extracted from the prompt.
         */
        final String forcedTool = force.orElseGet(() -> context.get("tool"));

        if (StringUtils.isNotBlank(forcedTool)) {
            return tools.stream()
                    .filter(tool -> tool.getName().equals(forcedTool))
                    .findFirst()
                    .map(tool -> new ToolCall(tool, new ToolDefinition(tool.getName(), List.of())))
                    .orElse(null);
        }

        final String toolPrompt = getToolsPrompt(prompt);
        final ToolDefinition toolDefinition = selectOllamaTool(toolPrompt);
        return getToolCallFromToolDefinition(toolDefinition);
    }

    /**
     * The LLM will sometimes return invalid JSON for tool selection, so we retry a few times
     *
     * @param toolPrompt The tool prompt
     * @return The selected tool
     */
    private ToolDefinition selectOllamaTool(final String toolPrompt) {
        return Try.of(() -> callOllama(toolPrompt))
                .map(OllamaResponse::response)
                .mapTry(this::parseResponseAsToolDefinitions)
                .mapTry(validateList::throwIfEmpty)
                .mapTry(List::getFirst)
                .get();
    }

    @Nullable
    private ToolCall getToolCallFromToolDefinition(final ToolDefinition toolDefinition) {
        return tools.stream().filter(tool -> tool.getName().equals(toolDefinition.toolName()))
                .findFirst()
                .map(tool -> new ToolCall(tool, toolDefinition))
                .orElse(null);
    }

    private String getToolsPrompt(final String prompt) {
        return toolBuilder.buildToolPrompt(tools.stream().toList(), prompt);
    }

    private OllamaResponse callOllama(final String llmPrompt) {
        return Try.withResources(() -> ClientBuilder
                        .newBuilder()
                        .readTimeout(NumberUtils.toInt(timeout.orElse("10"), 10), TimeUnit.MINUTES)
                        .build())
                .of(client ->
                        ollamaClient.callOllama(
                                client,
                                new OllamaGenerateBody(toolModel.get(), llmPrompt, false)))
                .get();
    }

    private List<ToolDefinition> parseResponseAsToolDefinitions(final String response) throws JsonProcessingException {
        return Try.of(() -> jsonDeserializer.deserialize(response, ToolDefinition[].class))
                .map(List::of)
                .recoverWith(error -> Try.of(() -> parseResponseAsToolDefinitionsFallback(response)))
                .map(validateList::throwIfEmpty)
                .onFailure(e -> logger.warning("Failed to parse tool definition, or list was empty. The JSON returned by the LLM was:\n" + response))
                .get();
    }

    private List<ToolDefinition> parseResponseAsToolDefinitionsFallback(final String response) throws JsonProcessingException {
        final ToolDefinitionFallback[] tool = jsonDeserializer.deserialize(response, ToolDefinitionFallback[].class);
        return Arrays.stream(tool).map(t -> new ToolDefinition(t.toolName(), List.of())).toList();
    }
}
