package secondbrain.domain.context;

import org.jspecify.annotations.Nullable;

import java.util.Map;

public interface EnvironmentSettings extends Map<String, String> {
    EnvironmentSettings add(String key, String value);

    EnvironmentSettings addToolCall(String value);

    @Nullable
    String getToolCall();
}
