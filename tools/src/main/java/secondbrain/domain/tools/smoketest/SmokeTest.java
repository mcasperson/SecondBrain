package secondbrain.domain.tools.smoketest;

import jakarta.enterprise.context.ApplicationScoped;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;

import java.util.List;
import java.util.Map;

/**
 * A tool that returns a greeting message.
 */
@ApplicationScoped
public class SmokeTest implements Tool<Void> {
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
    public List<RagDocumentContext<Void>> getContext(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {
        return List.of();
    }

    @Override
    public RagMultiDocumentContext<Void> call(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {
        return new RagMultiDocumentContext<Void>(prompt).updateResponse("Test succeeded!");
    }

    @Override
    public String getContextLabel() {
        return "Unused";
    }
}
