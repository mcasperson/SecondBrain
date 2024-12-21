package secondbrain.domain.debug;

import secondbrain.domain.tooldefs.ToolArgs;

import java.util.List;

public interface DebugToolArgs {
    String debugArgs(List<ToolArgs> args);
}
