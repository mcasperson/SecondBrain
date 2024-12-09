package secondbrain.domain.tools;

import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;

import java.util.List;
import java.util.Map;

public class TestTool implements Tool {

    @Override
    public String getName() {
        return "Test";
    }

    @Override
    public String getDescription() {
        return "Test Tool";
    }

    @Override
    public List<ToolArguments> getArguments() {
        return List.of(
                new ToolArguments("arg1", "description1", "default1"),
                new ToolArguments("arg2", "description2", "default2"));
    }

    @Override
    public RagMultiDocumentContext<?> call(Map<String, String> context, String prompt, List<ToolArgs> arguments) {
        return new RagMultiDocumentContext<>("");
    }
}
