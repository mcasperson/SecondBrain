package secondbrain.domain.args;

import secondbrain.domain.sanitize.SanitizeDocument;
import secondbrain.domain.tooldefs.ToolArgs;

import java.util.List;

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
    String getArgument(
            List<ToolArgs> arguments,
            String argName,
            String defaultValue);

    /**
     * Get the argument value from the list of arguments.
     *
     * @param arguments    The list of arguments
     * @param sanitizers   The list of sanitizers to apply to the argument
     * @param argName      The name of the argument to return
     * @param defaultValue The default value to return if the argument is not found
     * @return The argument, or the default value if the argument was not found
     */
    String getArgument(
            List<ToolArgs> arguments,
            List<SanitizeDocument> sanitizers,
            String argName,
            String defaultValue);
}
