package secondbrain.domain.handler;


import java.util.Map;

/**
 * Interface that defines a prompt handler
 */
public interface PromptHandler {
    /**
     * Handles a prompt
     *
     * @param context Key/value pairs that define the context of the prompt. Typically, this is used to pass authentication details.
     * @param prompt  The prompt to handle
     * @return The prompt ollamaResponse
     */
    String handlePrompt(Map<String, String> context, String prompt);
}
