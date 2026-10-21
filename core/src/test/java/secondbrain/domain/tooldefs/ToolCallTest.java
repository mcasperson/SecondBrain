package secondbrain.domain.tooldefs;

import org.junit.jupiter.api.Test;
import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("NullAway")
class ToolCallTest {

    private static final Logger LOGGER = Logger.getLogger(ToolCallTest.class.getName());

    @Test
    void callForwardsContextPromptsAndArgumentsToTheTool() {
        final RecordingTool tool = new RecordingTool();
        final List<ToolArgs> args = List.of(new ToolArgs("days", "7", true));
        final Map<String, String> context = Map.of("key", "value");
        final List<String> prompts = List.of("prompt one", "prompt two");

        final RagMultiDocumentContext<?> result = new ToolCall(tool, new ToolDefinition("recording", args))
                .call(context, prompts, LOGGER);

        assertSame(tool.result, result);
        assertEquals(1, tool.calls);
        assertSame(context, tool.receivedContext);
        assertSame(prompts, tool.receivedPrompts);
        assertEquals(args, tool.receivedArguments);
    }

    @Test
    void callPassesEmptyArgumentsWhenToolDefinitionHasNone() {
        final RecordingTool tool = new RecordingTool();

        new ToolCall(tool, new ToolDefinition("recording", null)).call(Map.of(), List.of("prompt"), LOGGER);

        assertEquals(List.of(), tool.receivedArguments);
    }

    private static class RecordingTool implements Tool<Void> {
        private final RagMultiDocumentContext<Void> result = new RagMultiDocumentContext<>("result");
        private int calls;
        private Map<String, String> receivedContext;
        private List<String> receivedPrompts;
        private List<ToolArgs> receivedArguments;

        @Override
        public String getName() {
            return "recording";
        }

        @Override
        public String getDescription() {
            return "Records calls";
        }

        @Override
        public List<ToolArguments> getArguments() {
            return List.of();
        }

        @Override
        public List<RagDocumentContext<Void>> getContext(final Map<String, String> environmentSettings,
                                                         final List<String> prompts, final List<ToolArgs> arguments) {
            return List.of();
        }

        @Override
        public RagMultiDocumentContext<Void> call(final Map<String, String> environmentSettings,
                                                  final List<String> prompts, final List<ToolArgs> arguments) {
            calls++;
            receivedContext = environmentSettings;
            receivedPrompts = prompts;
            receivedArguments = arguments;
            return result;
        }

        @Override
        public String getContextLabel() {
            return "Recording";
        }

        @Override
        public int contextHashCode(final Map<String, String> environmentSettings,
                                   final List<String> prompts, final List<ToolArgs> arguments) {
            return 0;
        }
    }
}
