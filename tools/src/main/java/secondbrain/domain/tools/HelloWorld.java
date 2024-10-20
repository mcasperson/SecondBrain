package secondbrain.domain.tools;

import com.google.common.collect.ImmutableList;
import jakarta.enterprise.context.Dependent;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;

import java.util.List;
import java.util.Map;

/**
 * A tool that returns a greeting message.
 */
@Dependent
public class HelloWorld implements Tool {
    @Override
    public String getName() {
        return HelloWorld.class.getSimpleName();
    }

    @Override
    public String getDescription() {
        return "Returns a greeting message";
    }

    @Override
    public List<ToolArguments> getArguments() {
        return ImmutableList.of(new ToolArguments(
                "greeting",
                "The greeting to display",
                "World"));
    }

    @Override
    public String call(
            final Map<String, String> context,
            final String prompt,
            final List<ToolArgs> arguments) {
        if (arguments.size() != 1) {
            return "Hello, World!";
        }

        return arguments.getFirst().argValue();
    }
}
