package secondbrain.domain.tools;

import java.util.List;

public interface ToolBuilder {
    String buildToolJson(List<Tool> tools);
    String buildToolPrompt(List<Tool> tools, String prompt);
}
