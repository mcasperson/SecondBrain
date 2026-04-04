package secondbrain.domain.collections;

import org.apache.tika.utils.StringUtils;
import org.jspecify.annotations.Nullable;

import java.util.Map;

import static com.google.common.base.Preconditions.checkNotNull;

public final class MapUtils {
    @Nullable public static String getOrDefaultIfBlank(final Map<String, String> map, final String key, @Nullable final String defaultValue) {
        if (!map.containsKey(key) || StringUtils.isBlank(map.get(key))) {
            return defaultValue;
        }

        return map.get(key);
    }

    public static String getOrNotNullDefaultIfBlank(final Map<String, String> map, final String key, final String defaultValue) {
        checkNotNull(defaultValue, "Default value cannot be null");

        if (!map.containsKey(key) || StringUtils.isBlank(map.get(key))) {
            return defaultValue;
        }

        return map.get(key);
    }
}
