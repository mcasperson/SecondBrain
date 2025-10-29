package secondbrain.domain.tooldefs;


import secondbrain.domain.context.RagMultiDocumentContext;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Represents an execution of a tool. It contains the tool and the tool definition.
 *
 * @param tool           The tool to call
 * @param toolDefinition The arguments to pass to the tool
 */
public record ToolCall(Tool<?> tool, ToolDefinition toolDefinition) {
    public RagMultiDocumentContext<?> call(final Map<String, String> context, final String prompt, final Logger logger) {
        logger.log(Level.FINE, "Calling tool {0} with {1}", new Object[]{tool, toolDefinition});
        return tool.call(context, prompt, Objects.requireNonNullElse(toolDefinition.toolArgs(), List.of()));
    }
}
