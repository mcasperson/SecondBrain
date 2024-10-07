package secondbrain.domain.tools.impl;

import jakarta.enterprise.context.Dependent;
import jakarta.validation.constraints.NotNull;
import secondbrain.domain.tools.Tool;
import secondbrain.domain.tools.ToolArgs;
import secondbrain.domain.tools.ToolArguments;

import java.util.ArrayList;
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
