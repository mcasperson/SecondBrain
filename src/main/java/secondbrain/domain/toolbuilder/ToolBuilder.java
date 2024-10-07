package secondbrain.domain.toolbuilder;

import secondbrain.domain.tooldefs.Tool;

import java.util.List;

public interface ToolBuilder {
    String buildToolJson(List<Tool> tools);
    String buildToolPrompt(List<Tool> tools, String prompt);
}
