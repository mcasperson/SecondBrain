package secondbrain.domain.tooldefs;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Represents a tool argument. This is sent back by the LLM
 *
 * @param argName  The argument name
 * @param argValue The argument value
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ToolArgs(String argName, String argValue) {
}
