package secondbrain.domain.tooldefs;

import org.jspecify.annotations.NonNull;

/**
 * Represents the definition of a tool argument. This is sent to the LLM.
 *
 * @param name         The argument name
 * @param description  The argument description
 * @param defaultValue The default value of the argument
 */
public record ToolArguments(
        @NonNull String name,
        @NonNull String description,
        @NonNull String defaultValue) {
}
