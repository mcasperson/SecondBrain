package secondbrain.domain.debug;

import jakarta.enterprise.context.ApplicationScoped;
import secondbrain.domain.tooldefs.ToolArgs;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A tool that prints debug information about the tool arguments.
 */
@ApplicationScoped
public class DebugToolArgsKeyValue implements DebugToolArgs {


    @Override
    public String debugArgs(final List<ToolArgs> args) {

        return args.stream()
                .map(arg -> "* " + arg.argName() + ": " + arg.argValue())
                .collect(Collectors.joining(System.lineSeparator()));
    }
}
