package secondbrain.domain.handler;

import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ProcessingException;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.toolbuilder.ToolSelector;
import secondbrain.domain.tooldefs.ToolCall;

import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Implementation of the prompt handler.
 */
@ApplicationScoped
public class PromptHandlerOllama implements PromptHandler {
    private static final int TOOL_RETRY = 10;

    @Inject
    @ConfigProperty(name = "sb.ollama.model", defaultValue = "llama3.2")
    String model;

    /**
     * The model to select a tool can be specifically defined. This allows tool calling
     * to use one model, and the tool itself to use another. Only llama3 variants are supported.
     */
    @Inject
    @ConfigProperty(name = "sb.ollama.toolmodel", defaultValue = "llama3.2")
    Optional<String> toolModel;

    @Inject
    private Logger logger;

    @Inject
    private ToolSelector toolSelector;


    public String handlePrompt(final Map<String, String> context, final String prompt) {

        return handlePromptWithRetry(context, prompt, 1);
    }

    public String handlePromptWithRetry(final Map<String, String> context, final String prompt, int count) {
        return Try.of(() -> toolSelector.getTool(prompt))
                .map(toolCall -> callTool(toolCall, context, prompt))
                .recover(ProcessingException.class, e -> "Failed to connect to Ollama. You must install Ollama from https://ollama.com/download: " + e.toString())
                .recoverWith(error -> Try.of(() -> {
                    // Selecting the wrong tool can manifest itself as an exception
                    if (count < TOOL_RETRY) {
                        return handlePromptWithRetry(context, prompt, count + 1);
                    }
                    throw error;
                }))
                .recover(Throwable.class, e -> "Failed to find a tool or call it: " + ExceptionUtils.getRootCauseMessage(e))
                .get();
    }


    private String callTool(@Nullable final ToolCall toolCall, final Map<String, String> context, final String prompt) {
        if (toolCall == null) {
            return "No tool found";
        }

        logger.log(Level.INFO, "Calling tool " + toolCall.tool().getName());

        return toolCall.call(context, prompt);
    }
}
