package secondbrain.domain.tools;

import secondbrain.domain.context.RagDocumentContext;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.tooldefs.MetaObjectResult;
import secondbrain.domain.tooldefs.Tool;
import secondbrain.domain.tooldefs.ToolArgs;
import secondbrain.domain.tooldefs.ToolArguments;

import java.util.List;
import java.util.Map;

public class TestTool implements Tool<Void> {

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
    public List<RagDocumentContext<Void>> getContext(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {
        return List.of();
    }

    @Override
    public List<MetaObjectResult> getMetadata(List<RagDocumentContext<Void>> context, Map<String, String> environmentSettings, String prompt, List<ToolArgs> arguments) {
        return List.of();
    }

    @Override
    public RagMultiDocumentContext<Void> call(
            final Map<String, String> environmentSettings,
            final String prompt,
            final List<ToolArgs> arguments) {
        return new RagMultiDocumentContext<>("");
    }

    @Override
    public String getContextLabel() {
        return "Unused";
    }
}
