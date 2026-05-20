package secondbrain.domain.tools.smoketest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.objects.ToStringGenerator;
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
    @Inject
    private SmokeTestConfig config;

    @Override
    public int contextHashCode(final Map<String, String> environmentSettings, final String prompt, final List<ToolArgs> arguments) {
        final SmokeTestConfig.LocalArguments parsedArgs = config.new LocalArguments(arguments, prompt, environmentSettings);
        return parsedArgs.hashCode();
    }

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

@ApplicationScoped
class SmokeTestConfig {
    @Inject
    private ToStringGenerator toStringGenerator;

    public ToStringGenerator getToStringGenerator() {
        return toStringGenerator;
    }

    public class LocalArguments {
        private final List<ToolArgs> arguments;
        private final String prompt;
        private final Map<String, String> context;

        public LocalArguments(final List<ToolArgs> arguments, final String prompt, final Map<String, String> context) {
            this.arguments = List.copyOf(arguments);
            this.prompt = prompt;
            this.context = Map.copyOf(context);
        }

        @Override
        public String toString() {
            return getToStringGenerator().generateGetterConfig(this);
        }

        @Override
        public int hashCode() {
            return getToStringGenerator().generateHashGetterConfig(this);
        }
    }
}
