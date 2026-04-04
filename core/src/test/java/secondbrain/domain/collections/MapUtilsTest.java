package secondbrain.domain.collections;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SuppressWarnings("NullAway")
class MapUtilsTest {

    @Test
    void testGetOrDefaultIfBlank_KeyPresent_NonBlankValue() {
        Map<String, String> map = Map.of("key", "value");
        assertEquals("value", MapUtils.getOrDefaultIfBlank(map, "key", "default"),
                "Expected the actual value when the key is present and value is non-blank");
    }

    @Test
    void testGetOrDefaultIfBlank_KeyAbsent() {
        Map<String, String> map = Map.of("otherKey", "value");
        assertEquals("default", MapUtils.getOrDefaultIfBlank(map, "key", "default"),
                "Expected the default value when the key is absent");
    }

    @Test
    void testGetOrDefaultIfBlank_KeyPresent_BlankValue() {
        Map<String, String> map = new HashMap<>();
        map.put("key", "   ");
        assertEquals("default", MapUtils.getOrDefaultIfBlank(map, "key", "default"),
                "Expected the default value when the value is blank (whitespace only)");
    }

    @Test
    void testGetOrDefaultIfBlank_KeyPresent_EmptyValue() {
        Map<String, String> map = new HashMap<>();
        map.put("key", "");
        assertEquals("default", MapUtils.getOrDefaultIfBlank(map, "key", "default"),
                "Expected the default value when the value is an empty string");
    }

    @Test
    void testGetOrDefaultIfBlank_KeyPresent_NullValue() {
        Map<String, String> map = new HashMap<>();
        map.put("key", null);
        assertEquals("default", MapUtils.getOrDefaultIfBlank(map, "key", "default"),
                "Expected the default value when the value is null");
    }

    @Test
    void testGetOrDefaultIfBlank_EmptyMap() {
        Map<String, String> map = Map.of();
        assertEquals("default", MapUtils.getOrDefaultIfBlank(map, "key", "default"),
                "Expected the default value when the map is empty");
    }

    @Test
    void testGetOrDefaultIfBlank_KeyPresent_ValueWithLeadingAndTrailingSpaces() {
        Map<String, String> map = Map.of("key", "  value  ");
        assertEquals("  value  ", MapUtils.getOrDefaultIfBlank(map, "key", "default"),
                "Expected the actual value (with surrounding spaces) when it is non-blank");
    }
}

