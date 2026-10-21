package secondbrain.domain.context;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HashMapEnvironmentSettingsTest {
    @Test
    void toolCallsKeyIsStable() {
        assertEquals("TOOL_CALLS", HashMapEnvironmentSettings.TOOL_CALLS);
    }

    @Test
    void defaultConstructorCreatesEmptySettings() {
        final HashMapEnvironmentSettings settings = new HashMapEnvironmentSettings();

        assertTrue(settings.isEmpty());
        assertNull(settings.getToolCall());
    }

    @Test
    void mapConstructorCopiesTheEntries() {
        final Map<String, String> source = new HashMap<>(Map.of("a", "1", "b", "2"));

        final HashMapEnvironmentSettings settings = new HashMapEnvironmentSettings(source);
        source.put("c", "3");

        assertEquals(Map.of("a", "1", "b", "2"), settings);
    }

    @Test
    void mapConstructorExposesExistingToolCalls() {
        final HashMapEnvironmentSettings settings = new HashMapEnvironmentSettings(Map.of(HashMapEnvironmentSettings.TOOL_CALLS, "slack[1]"));

        assertEquals("slack[1]", settings.getToolCall());
    }

    @Test
    void addStoresTheValueAndReturnsTheSameInstance() {
        final HashMapEnvironmentSettings settings = new HashMapEnvironmentSettings();

        final EnvironmentSettings result = settings.add("key", "value");

        assertSame(settings, result);
        assertEquals("value", settings.get("key"));
    }

    @Test
    void addReplacesAnExistingValue() {
        final HashMapEnvironmentSettings settings = new HashMapEnvironmentSettings(Map.of("key", "old"));

        settings.add("key", "new");

        assertEquals(Map.of("key", "new"), settings);
    }

    @Test
    void firstToolCallIsStoredWithoutSeparator() {
        final HashMapEnvironmentSettings settings = new HashMapEnvironmentSettings();

        final EnvironmentSettings result = settings.addToolCall("slack", "42");

        assertSame(settings, result);
        assertEquals("slack[42]", settings.getToolCall());
        assertEquals("slack[42]", settings.get(HashMapEnvironmentSettings.TOOL_CALLS));
    }

    @Test
    void subsequentToolCallsAreChainedInOrder() {
        final HashMapEnvironmentSettings settings = new HashMapEnvironmentSettings();

        settings.addToolCall("slack", "1").addToolCall("gong", "2").addToolCall("zendesk", "3");

        assertEquals("slack[1]->gong[2]->zendesk[3]", settings.getToolCall());
    }

    @Test
    void emptyExistingToolCallsIsTreatedAsNoToolCalls() {
        final HashMapEnvironmentSettings settings = new HashMapEnvironmentSettings(Map.of(HashMapEnvironmentSettings.TOOL_CALLS, ""));

        settings.addToolCall("slack", "1");

        assertEquals("slack[1]", settings.getToolCall());
    }

    @Test
    void toolCallAppendsToValueSetWithAdd() {
        final HashMapEnvironmentSettings settings = new HashMapEnvironmentSettings();
        settings.add(HashMapEnvironmentSettings.TOOL_CALLS, "upstream[0]");

        settings.addToolCall("slack", "1");

        assertEquals("upstream[0]->slack[1]", settings.getToolCall());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void blankToolIsRejected(final String tool) {
        final HashMapEnvironmentSettings settings = new HashMapEnvironmentSettings();

        assertThrows(IllegalArgumentException.class, () -> settings.addToolCall(tool, "1"));
        assertNull(settings.getToolCall());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void blankIdIsRejected(final String id) {
        final HashMapEnvironmentSettings settings = new HashMapEnvironmentSettings();

        assertThrows(IllegalArgumentException.class, () -> settings.addToolCall("slack", id));
        assertNull(settings.getToolCall());
    }
}
