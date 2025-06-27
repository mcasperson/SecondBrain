package secondbrain.domain.prompt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class PromptBuilderGemmaTest {

    @Test
    public void testModelRegex() {
        PromptBuilderGemma promptBuilder = new PromptBuilderGemma();
        String regex = promptBuilder.modelRegex();
        assertNotNull(regex);

        assertTrue("hf.co/unsloth/gemma-3-27b-it-GGUF:Q4_K_M".matches(regex));
        assertTrue("gemma2".matches(regex));
        assertTrue("gemma3n".matches(regex));
        assertTrue("gemma3".matches(regex));
        assertTrue("gemma3:27b".matches(regex));
        assertFalse("mistral".matches(regex));
    }

}