package secondbrain.domain.context;

import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;

import java.util.*;

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
    public EnvironmentSettings addToolCall(final String tool, final String id) {
        checkArgument(StringUtils.isNoneBlank(tool));
        checkArgument(StringUtils.isNoneBlank(id));

        final String existing = Objects.requireNonNullElse(this.get(TOOL_CALLS), "");
        if (existing.isEmpty()) {
            this.put(TOOL_CALLS, tool + "[" + id + "]");
        } else {
            this.put(TOOL_CALLS, existing + "->" + tool + "[" + id + "]");
        }

        return this;
    }

    @Override
    @Nullable
    public String getToolCall() {
        return this.get(TOOL_CALLS);
    }
}