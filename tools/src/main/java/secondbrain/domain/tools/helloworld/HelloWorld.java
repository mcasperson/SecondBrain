package secondbrain.domain.tools.helloworld;

import com.google.common.collect.ImmutableList;
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
public class HelloWorld implements Tool<Void> {
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

    public List<RagDocumentContext<Void>> getContext(
            final Map<String, String> context,
            final String prompt,
            final List<ToolArgs> arguments) {
        return List.of();
    }

    @Override
    public RagMultiDocumentContext<Void> call(
            final Map<String, String> context,
            final String prompt,
            final List<ToolArgs> arguments) {
        if (arguments.size() != 1) {
            return new RagMultiDocumentContext<>("Hello, World!");
        }

        return new RagMultiDocumentContext<>(arguments.getFirst().argValue());
    }

    @Override
    public String getContextLabel() {
        return "Unused";
    }
}
