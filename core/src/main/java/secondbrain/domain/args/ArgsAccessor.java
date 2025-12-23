package secondbrain.domain.args;

import org.jspecify.annotations.Nullable;
import secondbrain.domain.sanitize.SanitizeArgument;
import secondbrain.domain.tooldefs.ToolArgs;

import java.util.List;
import java.util.Map;

/**
 * A service for extracting an argument from a list of arguments.
 */
public interface ArgsAccessor {
    /**
     * Get the argument value from the list of arguments.
     *
     * @param arguments    The list of arguments
     * @param argName      The name of the argument to return
     * @param defaultValue The default value to return if the argument is not found
     * @return The argument, or the default value if the argument was not found
     */
    Argument getArgument(
            @Nullable List<ToolArgs> arguments,
            @Nullable String argName,
            String defaultValue);

    /**
     * Get the argument value from the list of arguments.
     *
     * @param arguments    The list of arguments
     * @param argName      The name of the argument to return
     * @param defaultValue The default value to return if the argument is not found
     * @return The argument, or the default value if the argument was not found
     */
    Argument getArgument(
            @Nullable ArgsAccessorSystemProperty systemProperty,
            @Nullable List<ToolArgs> arguments,
            @Nullable Map<String, String> context,
            @Nullable String argName,
            @Nullable String contextName,
            String defaultValue);

    /**
     * Get the argument value as a comma separated list from the list of arguments.
     *
     * @param arguments    The list of arguments
     * @param argName      The name of the argument to return
     * @param defaultValue The default value to return if the argument is not found
     * @return The argument, or the default value if the argument was not found
     */
    List<Argument> getArgumentList(
            @Nullable ArgsAccessorSystemProperty systemProperty,
            @Nullable List<ToolArgs> arguments,
            @Nullable Map<String, String> context,
            @Nullable String argName,
            @Nullable String contextName,
            String defaultValue);

    /**
     * Get the argument value from the list of arguments.
     *
     * @param arguments    The list of arguments
     * @param sanitizers   The list of sanitizers to apply to the argument
     * @param prompt       The users prompt
     * @param argName      The name of the argument to return
     * @param defaultValue The default value to return if the argument is not found
     * @return The argument, or the default value if the argument was not found
     */
    Argument getArgument(
            @Nullable List<ToolArgs> arguments,
            @Nullable List<SanitizeArgument> sanitizers,
            @Nullable String prompt,
            @Nullable String argName,
            String defaultValue);
}
