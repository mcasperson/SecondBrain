package secondbrain.domain.prompt;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class PromptBuilderQwenTest {

    @Test
    public void testModelRegex() {
        PromptBuilderQwen promptBuilder = new PromptBuilderQwen();
        String regex = promptBuilder.modelRegex();
        assertNotNull(regex);

        assertTrue("qwen2".matches(regex));
        assertTrue("qwen2.0".matches(regex));
        assertTrue("qwen2.5".matches(regex));
        assertTrue("qwen3".matches(regex));
        assertTrue("qwq".matches(regex));
        assertFalse("mistral".matches(regex));
        assertFalse("gemma".matches(regex));
    }

    @Test
    public void testBuildContextPrompt() {
        PromptBuilderQwen promptBuilder = new PromptBuilderQwen();

        // Test with title and prompt
        String result = promptBuilder.buildContextPrompt("Test Title", "Test Prompt");
        assertEquals("---------------------\nTest Title:\nTest Prompt\n---------------------", result);

        // Test with blank title
        result = promptBuilder.buildContextPrompt("", "Test Prompt");
        assertEquals("---------------------\nTest Prompt\n---------------------", result);

        // Test with blank prompt
        result = promptBuilder.buildContextPrompt("Test Title", "");
        assertEquals("", result);
    }

    @Test
    public void testBuildFinalPrompt() {
        PromptBuilderQwen promptBuilder = new PromptBuilderQwen();

        // Test with instructions
        String result = promptBuilder.buildFinalPrompt(
                "Test Instructions",
                "Test Context",
                "Test Prompt");
        assertEquals("<|im_start|>system\nTest Instructions\nTest Context\n<|im_end|>\n\n<|im_start|>user\nTest Prompt\n<|im_end|>\n<|im_start|>assistant", result);

        // Test without instructions
        result = promptBuilder.buildFinalPrompt(
                "",
                "Test Context",
                "Test Prompt");
        assertEquals("Test Context\n<|im_end|>\n\n<|im_start|>user\nTest Prompt\n<|im_end|>\n<|im_start|>assistant", result);
    }
}