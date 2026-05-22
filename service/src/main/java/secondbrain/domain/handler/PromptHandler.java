package secondbrain.domain.handler;

import java.util.List;
import java.util.Map;

/**
 * Interface that defines a prompt handler
 */
public interface PromptHandler {
    /**
     * Handles a prompt. Context is collected once using the first prompt,
     * and then used to generate a response.
     *
     * @param context Key/value pairs that define the context of the prompt.
     *                These values are known by the environment rather than being defined by the user.
     *                Typically, this is used to pass authentication details.
     * @param prompts The list of prompts to handle.
     * @return The response from the LLM.
     */
    PromptHandlerResponse handlePrompt(Map<String, String> context, List<String> prompts);
}
