package secondbrain.domain.args;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.constraints.NotNull;
import secondbrain.domain.tooldefs.ToolArgs;

import java.util.List;

@ApplicationScoped
public class ArgsAccessor {
    public String getArgument(
            @NotNull final List<ToolArgs> arguments,
            @NotNull final String argName,
            final String defaultValue) {
        return arguments.stream()
                .filter(arg -> arg.argName().equals(argName))
                .findFirst().map(ToolArgs::argValue)
                .orElse(defaultValue);
    }
}
