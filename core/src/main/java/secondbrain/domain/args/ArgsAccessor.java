package secondbrain.domain.args;

import jakarta.enterprise.context.ApplicationScoped;
import secondbrain.domain.tooldefs.ToolArgs;

import java.util.List;

@ApplicationScoped
public class ArgsAccessor {
    public String getArgument(
            final List<ToolArgs> arguments,
            final String argName,
            final String defaultValue) {
        return arguments.stream()
                .filter(arg -> arg.argName().equals(argName))
                .findFirst().map(ToolArgs::argValue)
                .orElse(defaultValue);
    }
}
