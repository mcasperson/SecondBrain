package secondbrain.domain.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IndividualContextTest {

    @Test
    void updateContextReplacesContextAndKeepsIdAndMeta() {
        final IndividualContext<String, String> original = new IndividualContext<>("id-1", "raw", "meta");

        final IndividualContext<Integer, String> updated = original.updateContext(42);

        assertEquals("id-1", updated.id());
        assertEquals(42, updated.context());
        assertEquals("meta", updated.meta());
    }

    @Test
    void updateContextDoesNotModifyTheOriginal() {
        final IndividualContext<String, String> original = new IndividualContext<>("id-1", "raw", "meta");

        original.updateContext("processed");

        assertEquals("raw", original.context());
    }
}
