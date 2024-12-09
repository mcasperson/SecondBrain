package secondbrain.domain.tooldefs;


import secondbrain.domain.context.RagMultiDocumentContext;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Represents an execution of a tool. It contains the tool and the tool definition.
 *
 * @param tool           The tool to call
 * @param toolDefinition The arguments to pass to the tool
 */
public record ToolCall(Tool tool, ToolDefinition toolDefinition) {
    public RagMultiDocumentContext<?> call(final Map<String, String> context, final String prompt) {
        return tool.call(context, prompt, Objects.requireNonNullElse(toolDefinition.toolArgs(), List.of()));
    }
}
