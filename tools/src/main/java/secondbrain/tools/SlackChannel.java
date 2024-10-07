package secondbrain.tools;

import jakarta.enterprise.context.Dependent;
import jakarta.validation.constraints.NotNull;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;

import java.util.List;
import java.util.Map;

@Dependent
public class SlackChannel implements Tool {
    @Override
    @NotNull
    public String getName() {
        return SlackChannel.class.getSimpleName();
    }

    @Override
    @NotNull
    public String getDescription() {
        return "Returns messages from a Slack channel";
    }

    @Override
    @NotNull
    public List<ToolArguments> getArguments() {
        return List.of(
                new ToolArguments("channel", "The Slack channel to read", "general"),
                new ToolArguments("count", "The number of messages to return", "10")
        );
    }

    @Override
    @NotNull
    public String call(
            @NotNull final Map<String, String> context,
            @NotNull final String prompt,
            @NotNull final List<ToolArgs> arguments) {
        if (arguments.size() != 2) {
            return "Hi from Slack!";
        }

        return "Hi from Slack channel " + arguments.stream()
                .filter(arg -> arg.argName().equals("channel"))
                .findFirst().orElse(new ToolArgs("unknown", "unknown"))
                .argValue();
    }
}
