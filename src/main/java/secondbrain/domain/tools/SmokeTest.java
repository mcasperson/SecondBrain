package secondbrain.domain.tools;

import jakarta.enterprise.context.Dependent;
import jakarta.validation.constraints.NotNull;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;

import java.util.List;

/**
 * A tool that returns a greeting message.
 */
@Dependent
public class SmokeTest implements Tool {
    @Override
    @NotNull public String getName() {
        return SmokeTest.class.getSimpleName();
    }

    @Override
    @NotNull public String getDescription() {
        return "Used for smoke testing";
    }

    @Override
    @NotNull public List<ToolArguments> getArguments() {
        return List.of();
    }

    @Override
    @NotNull public String call(final @NotNull List<ToolArgs> arguments) {
        return "Test succeeded!";
    }
}
