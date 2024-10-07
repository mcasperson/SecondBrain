package secondbrain.tools;

import jakarta.enterprise.context.Dependent;
import jakarta.validation.constraints.NotNull;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;

import java.util.ArrayList;
import java.util.List;

/**
 * A tool that returns a greeting message.
 */
@Dependent
public class HelloWorld implements Tool {
    @Override
    @NotNull public String getName() {
        return HelloWorld.class.getSimpleName();
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
