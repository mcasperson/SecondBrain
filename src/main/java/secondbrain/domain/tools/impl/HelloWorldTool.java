package secondbrain.domain.tools.impl;

import jakarta.enterprise.context.Dependent;
import jakarta.validation.constraints.NotNull;
import secondbrain.domain.tools.Tool;
import secondbrain.domain.tools.ToolArgs;
import secondbrain.domain.tools.ToolArguments;

import java.util.ArrayList;
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
        return new ArrayList<>() {{
            add(new ToolArguments("greeting","The greeting to display", "World"));
        }};
    }

    @Override
    @NotNull public String call(final @NotNull List<ToolArgs> arguments) {
        if (arguments.size() != 1) {
            return "Hello, World!";
        }

        return arguments.getFirst().argValue();
    }
}
