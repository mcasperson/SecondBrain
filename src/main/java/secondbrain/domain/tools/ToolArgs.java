package secondbrain.domain.tools;

/**
 * Represents a tool argument. This is sent back by the LLM
 * @param argName The argument name
 * @param argValue The argument value
 */
public record ToolArgs(String argName, String argValue) {
}
