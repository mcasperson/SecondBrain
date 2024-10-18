package secondbrain.domain.tooldefs;

import java.util.Map;

/**
 * The details of a call execution. This is a fallback for when the LLM returns an object for the toolArgs rather
 * than an array.
 *
 * @param toolName The name of the tool to call
 * @param toolArgs The arguments to pass to the tool
 */
public record ToolDefinitionFallback(String toolName, Map<String, String> toolArgs) {
}
