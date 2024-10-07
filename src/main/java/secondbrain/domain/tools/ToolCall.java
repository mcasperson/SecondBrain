package secondbrain.domain.tools;

/**
 * Represents an execution of a tool. It contains the tool and the tool definition.
 * @param tool The tool to call
 * @param toolDefinition The arguments to pass to the tool
 */
public record ToolCall(Tool tool, ToolDefinition toolDefinition) {
    public String call() {
        return tool.call(toolDefinition.toolArgs());
    }
}
