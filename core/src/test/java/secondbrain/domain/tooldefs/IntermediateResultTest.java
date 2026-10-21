package secondbrain.domain.tooldefs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IntermediateResultTest {

    @Test
    void updateContentReplacesContentAndKeepsFilename() {
        final IntermediateResult updated = new IntermediateResult("old", "result.txt").updateContent("new");

        assertEquals("new", updated.content());
        assertEquals("result.txt", updated.filename());
    }

    @Test
    void updateContentDoesNotModifyTheOriginal() {
        final IntermediateResult original = new IntermediateResult("old", "result.txt");

        original.updateContent("new");

        assertEquals("old", original.content());
    }
}
