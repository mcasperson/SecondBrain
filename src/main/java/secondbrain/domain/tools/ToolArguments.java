package secondbrain.domain.tools;

import org.jspecify.annotations.NonNull;

public record ToolArguments(
        @NonNull String name,
        @NonNull String description,
        @NonNull String defaultValue) {
}
