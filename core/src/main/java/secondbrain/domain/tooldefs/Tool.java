package secondbrain.domain.tooldefs;


import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;

import java.util.List;
import java.util.Map;

/**
 * Represents a tool that can be called by the LLM.
 */
public interface Tool<T> {
    /**
     * Gets the name of the tool.
     *
     * @return The name of the tool
     */
    String getName();

    /**
     * Gets the description of the tool.
     *
     * @return The description of the tool
     */
    String getDescription();

    /**
     * Gets the arguments received by the tool. These are sent to the LLM.
     *
     * @return The arguments of the tool
     */
    List<ToolArguments> getArguments();

    /**
     * Builds the initial context for the tool. This context is either the raw data from the upstream source
     * (think Slack messages etc.) or the processed version of the data (think git commits that have been
     * summarized into a paragraph).
     *
     * @param context   The context associated with the prompt. These are values that come from the environment (like credentials) rather than from the prompt.
     * @param prompt    The prompt.
     * @param arguments The arguments extracted from the prompt.
     * @return The individual items that make up the context for the prompt.
     */
    List<RagDocumentContext<T>> getContext(
            Map<String, String> context,
            String prompt,
            List<ToolArgs> arguments);

    /**
     * Calls the tool. Typically, this function will call getContext() to get the context for the prompt, and then
     * pass the context and the prompt to the LLM for the final answer.
     *
     * @param context   The context associated with the prompt. These are values that come from the environment (like credentials) rather than from the prompt.
     * @param prompt    The prompt.
     * @param arguments The arguments extracted from the prompt.
     * @return The result from the LLM and the contents that was used to generate the result.
     */
    RagMultiDocumentContext<T> call(Map<String, String> context,
                                    String prompt,
                                    List<ToolArgs> arguments);
}
