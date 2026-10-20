package secondbrain.domain.prompt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

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

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    public void buildContextPromptReturnsEmptyStringForBlankPrompt(final String prompt) {
        assertEquals("", new PromptBuilderGemma().buildContextPrompt("Title", prompt));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    public void buildContextPromptOmitsBlankTitle(final String title) {
        assertEquals("Some context\n", new PromptBuilderGemma().buildContextPrompt(title, "Some context"));
    }

    @Test
    public void buildContextPromptPrefixesTitle() {
        assertEquals("Slack Messages:\nSome context\n", new PromptBuilderGemma().buildContextPrompt("Slack Messages", "Some context"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    public void buildFinalPromptWithoutInstructions(final String instructions) {
        assertEquals("<start_of_turn>user\nthe context\nthe question<end_of_turn>\n<start_of_turn>model",
                new PromptBuilderGemma().buildFinalPrompt(instructions, "the context", "the question"));
    }

    @Test
    public void buildFinalPromptWithInstructions() {
        assertEquals("<start_of_turn>user\nbe brief\nthe context\nthe question<end_of_turn>\n<start_of_turn>model",
                new PromptBuilderGemma().buildFinalPrompt("be brief", "the context", "the question"));
    }
}
