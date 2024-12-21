package secondbrain.domain.toolbuilder;


import secondbrain.domain.tooldefs.Tool;

import java.util.List;

/**
 * Interface that defines a tool builder whose purpose is to build a prompt to be passed to an
 * LLM, which then selects the correct tool and its arguments.
 */
public interface ToolBuilder {
    /**
     * Builds a JSON string from a list of tools.
     *
     * @param tools The list of tools
     * @return The JSON representing the tools and their arguments
     */
    String buildToolJson(List<Tool<?>> tools);

    /**
     * Builds a prompt for the LLM to select a tool.
     *
     * @param tools  The list of tools to select from
     * @param prompt The end user's prompt
     * @return The prompt sent to the LLM to select a tool based on the end user's prompt
     */
    String buildToolPrompt(List<Tool<?>> tools, String prompt);
}
