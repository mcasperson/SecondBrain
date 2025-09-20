package secondbrain.domain.tools.helloworld;

import com.google.common.collect.ImmutableList;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.exceptionhandling.ExceptionMapping;
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
    @Inject
    private ExceptionMapping exceptionMapping;

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

        final Try<RagMultiDocumentContext<Void>> result = Try.of(() -> arguments.size() != 1 ? arguments.getFirst().argValue() : "Hello, World!")
                .map(response -> new RagMultiDocumentContext<Void>(prompt).updateResponse(response));

        return exceptionMapping.map(result).get();
    }

    @Override
    public String getContextLabel() {
        return "Unused";
    }
}
