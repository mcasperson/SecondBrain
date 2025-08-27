package secondbrain.domain.context;

import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static com.google.common.base.Preconditions.checkArgument;

public class HashMapEnvironmentSettings extends HashMap<String, String> implements EnvironmentSettings {
    public static final String TOOL_CALLS = "TOOL_CALLS";

    public HashMapEnvironmentSettings() {
    }

    public HashMapEnvironmentSettings(final Map<String, String> map) {
        super(map);
    }

    @Override
    public EnvironmentSettings add(final String key, final String value) {
        this.put(key, value);
        return this;
    }

    @Override
    public EnvironmentSettings addToolCall(final String value) {
        checkArgument(StringUtils.isNoneBlank(value));

        final String existing = Objects.requireNonNullElse(this.get(TOOL_CALLS), "");
        if (existing.isEmpty()) {
            this.put(TOOL_CALLS, value);
        } else {
            this.put(TOOL_CALLS, existing + "->" + value);
        }

        return this;
    }

    @Override
    @Nullable
    public String getToolCall() {
        return this.get(TOOL_CALLS);
    }
}
