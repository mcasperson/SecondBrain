package secondbrain.domain.debug;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.BooleanUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import secondbrain.domain.tooldefs.ToolArgs;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A tool that prints debug information about the tool arguments.
 */
@ApplicationScoped
public class DebugToolArgs {
    @Inject
    @ConfigProperty(name = "sb.tools.debug", defaultValue = "false")
    String debug;

    public String debugArgs(final List<ToolArgs> args, boolean includeLineBreak) {
        if (BooleanUtils.toBoolean(debug)) {
            final String debug = args.stream()
                    .map(arg -> arg.argName() + ": " + arg.argValue())
                    .collect(Collectors.joining("\n"));

            if (includeLineBreak) {
                return System.lineSeparator() + debug;
            }

            return debug;
        }
        return "";
    }
}
