package secondbrain.domain.tools;

import java.util.List;

public record ToolDefinition(String toolName, List<ToolArgs> toolArgs) {
}
