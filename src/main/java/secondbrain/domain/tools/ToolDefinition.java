package secondbrain.domain.tools;

import java.util.List;

/**
 * The details of a call execution.
 * @param toolName The name of the tool to call
 * @param toolArgs The arguments to pass to the tool
 */
public record ToolDefinition(String toolName, List<ToolArgs> toolArgs) {
}
