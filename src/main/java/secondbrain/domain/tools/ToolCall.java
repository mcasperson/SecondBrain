package secondbrain.domain.tools;

public record ToolCall(Tool tool, ToolDefinition toolDefinition) {
    public String call() {
        return tool.call(toolDefinition.toolArgs());
    }
}
