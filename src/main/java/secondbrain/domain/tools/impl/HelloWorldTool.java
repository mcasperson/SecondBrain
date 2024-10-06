package secondbrain.domain.tools.impl;

import jakarta.enterprise.context.Dependent;
import jakarta.validation.constraints.NotNull;
import secondbrain.domain.tools.Tool;
import secondbrain.domain.tools.ToolArguments;

import java.util.List;

@Dependent
public class HelloWorldTool implements Tool {
    @Override
    @NotNull public String getName() {
        return HelloWorldTool.class.getSimpleName();
    }

    @Override
    @NotNull public String getDescription() {
        return "Returns a greeting message";
    }

    @Override
    @NotNull public List<ToolArguments> getArguments() {
        return List.of();
    }

    @Override
    @NotNull public String call(final @NotNull List<ToolArguments> arguments) {
        return "Hello, World!";
    }
}
