package secondbrain.domain.tooldefs;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import secondbrain.domain.args.Argument;

/**
 * Represents a tool argument. This is sent back by the LLM
 *
 * @param argName  The argument name
 * @param argValue The argument value
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ToolArgs(String argName, String argValue, @JsonIgnore boolean trusted) {
    public Argument toArgument() {
        return new Argument(argValue, trusted);
    }
}
