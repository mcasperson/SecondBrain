package secondbrain.domain.tooldefs;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Objects;

/**
 * Represents an execution of a tool. It contains the tool and the tool definition.
 * @param tool The tool to call
 * @param toolDefinition The arguments to pass to the tool
 */
public record ToolCall(@NotNull Tool tool, @NotNull ToolDefinition toolDefinition) {
    public String call() {
        return tool.call(Objects.requireNonNullElse(toolDefinition.toolArgs(), List.of()));
    }
}
