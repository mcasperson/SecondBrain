package secondbrain.domain.handler;

import java.util.Map;

/**
 * Interface that defines a prompt handler
 */
public interface PromptHandler {
    /**
     * Handles a prompt
     *
     * @param context Key/value pairs that define the context of the prompt.
     *                These values are known by the environment rather than being defined by the user.
     *                Typically, this is used to pass authentication details.
     * @param prompt  The prompt to handle.
     * @return The response from the LLM.
     */
    String handlePrompt(Map<String, String> context, String prompt);
}
