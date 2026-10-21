package secondbrain.domain.debug;

import org.junit.jupiter.api.Test;
import secondbrain.domain.tooldefs.ToolArgs;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("NullAway")
class DebugToolArgsKeyValueTest {

    private final DebugToolArgsKeyValue debugToolArgs = new DebugToolArgsKeyValue();

    @Test
    void debugArgsReturnsEmptyStringForNoArgs() {
        assertEquals("", debugToolArgs.debugArgs(List.of()));
    }

    @Test
    void debugArgsFormatsSingleArgWithoutTrailingSeparator() {
        assertEquals("* days: 7", debugToolArgs.debugArgs(List.of(new ToolArgs("days", "7", true))));
    }

    @Test
    void debugArgsJoinsMultipleArgsWithLineSeparatorInOrder() {
        final String result = debugToolArgs.debugArgs(List.of(
                new ToolArgs("days", "7", true),
                new ToolArgs("entity", "Acme", false)));

        assertEquals("* days: 7" + System.lineSeparator() + "* entity: Acme", result);
    }

    @Test
    void debugArgsPrintsNullValueAsNull() {
        assertEquals("* days: null", debugToolArgs.debugArgs(List.of(new ToolArgs("days", null, true))));
    }
}
