package secondbrain.domain.prompt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

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

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    public void buildContextPromptReturnsEmptyStringForBlankPrompt(final String prompt) {
        assertEquals("", new PromptBuilderPhi().buildContextPrompt("Title", prompt));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    public void buildContextPromptOmitsBlankTitle(final String title) {
        assertEquals("<|system|>\nSome context\n<|end|>", new PromptBuilderPhi().buildContextPrompt(title, "Some context"));
    }

    @Test
    public void buildContextPromptPrefixesTitle() {
        assertEquals("<|system|>\nSlack Messages:\nSome context\n<|end|>", new PromptBuilderPhi().buildContextPrompt("Slack Messages", "Some context"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    public void buildFinalPromptWithoutInstructions(final String instructions) {
        assertEquals("the context<|user|>\nthe question\n<|end|>\n<|assistant|>",
                new PromptBuilderPhi().buildFinalPrompt(instructions, "the context", "the question"));
    }

    @Test
    public void buildFinalPromptWithInstructions() {
        assertEquals("<|system|>\nbe brief\n<|end|>\nthe context\n<|user|>\nthe question\n<|end|>\n<|assistant|>",
                new PromptBuilderPhi().buildFinalPrompt("be brief", "the context", "the question"));
    }
}
