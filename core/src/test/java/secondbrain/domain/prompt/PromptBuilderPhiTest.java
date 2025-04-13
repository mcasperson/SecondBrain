package secondbrain.domain.prompt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class PromptBuilderPhiTest {

    @Test
    public void testModelRegex() {
        PromptBuilderPhi promptBuilder = new PromptBuilderPhi();
        String regex = promptBuilder.modelRegex();
        assertNotNull(regex);

        assertTrue("hf.co/unsloth/phi-4-GGUF".matches(regex));
        assertTrue("phi3:14b".matches(regex));
        assertTrue("phi4-mini".matches(regex));
        assertFalse("hf.co/unsloth/gemma2".matches(regex));
        assertFalse("mistral".matches(regex));
    }
}