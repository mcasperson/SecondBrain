package secondbrain.domain.tooldefs;

import java.util.List;

/**
 * Represents a tool that can be called by the LLM.
 */
public interface Tool {
    /**
     * Gets the name of the tool.
     * @return The name of the tool
     */
    String getName();
    /**
     * Gets the description of the tool.
     * @return The description of the tool
     */
    String getDescription();
    /**
     * Gets the arguments received by the tool. These are sent to the LLM.
     * @return The arguments of the tool
     */
    List<ToolArguments> getArguments();
    /**
     * Calls the tool.
     * @param arguments The arguments to pass to the tool. These are extracted by the LLM.
    */
    String call(List<ToolArgs> arguments);
}
