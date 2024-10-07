package secondbrain.domain.toolbuilder.impl;

import secondbrain.domain.tools.ToolArgs;

import java.util.List;

public record ToolDefinition(String toolName, List<ToolArgs> toolArgs) {
}
