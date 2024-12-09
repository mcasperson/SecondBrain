package secondbrain.domain.tools.smoketest;

import jakarta.enterprise.context.Dependent;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;

import java.util.List;
import java.util.Map;

/**
 * A tool that returns a greeting message.
 */
@Dependent
public class SmokeTest implements Tool {
    @Override
    public String getName() {
        return SmokeTest.class.getSimpleName();
    }

    @Override
    public String getDescription() {
        return "Used for smoke testing";
    }

    @Override
    public List<ToolArguments> getArguments() {
        return List.of();
    }

    @Override
    public RagMultiDocumentContext<?> call(
            final Map<String, String> context,
            final String prompt,
            final List<ToolArgs> arguments) {
        return new RagMultiDocumentContext<>("Test succeeded!");
    }
}
