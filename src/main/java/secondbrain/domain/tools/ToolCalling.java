package secondbrain.domain.tools;

import org.jspecify.annotations.NonNull;

import java.util.List;

public record ToolCalling(@NonNull String functionName,
                          @NonNull List<ToolArguments> arguments) {

}
