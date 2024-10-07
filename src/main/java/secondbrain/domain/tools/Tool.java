package secondbrain.domain.tools;

import java.util.List;

public interface Tool {
    String getName();
    String getDescription();
    List<ToolArguments> getArguments();
    String call(List<ToolArgs> arguments);
}
