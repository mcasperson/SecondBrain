package secondbrain.domain.tools;

import org.jspecify.annotations.NonNull;

public record ToolArguments(
        @NonNull String name,
        @NonNull String type,
        @NonNull String description,
        @NonNull String defaultValue,
        @NonNull String value) {
}
