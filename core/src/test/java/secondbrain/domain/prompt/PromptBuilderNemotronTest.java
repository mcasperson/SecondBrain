package secondbrain.domain.prompt;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(PromptBuilderNemotron.class)
class PromptBuilderNemotronTest {

	@Inject
	private PromptBuilderNemotron promptBuilder;

	@ParameterizedTest
	@ValueSource(strings = {"nemotron-3-nano", "nemotron-3-nano:30b", "nemotron-3-super"})
	void modelRegexMatchesNemotron3Models(final String model) {
		assertTrue(model.matches(promptBuilder.modelRegex()));
	}

	@ParameterizedTest
	@ValueSource(strings = {"nemotron", "nemotron-3", "nemotron-mini", "nemotron-30-nano", "Nemotron-3-nano", "llama3"})
	void modelRegexRejectsOtherModels(final String model) {
		assertFalse(model.matches(promptBuilder.modelRegex()));
	}

	@Test
	void buildContextPromptIncludesTitleAndPrompt() {
		assertEquals("<|im_start|>system\nTitle:\nBody\n<|im_end|>",
				promptBuilder.buildContextPrompt("Title", "Body"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "   ", "\t\n"})
	void buildContextPromptOmitsBlankTitle(final String title) {
		assertEquals("<|im_start|>system\nBody\n<|im_end|>",
				promptBuilder.buildContextPrompt(title, "Body"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "   ", "\t\n"})
	void buildContextPromptReturnsEmptyForBlankPrompt(final String prompt) {
		assertEquals("", promptBuilder.buildContextPrompt("Title", prompt));
	}

	@Test
	void buildFinalPromptWithInstructions() {
		assertEquals("Ctx<|im_start|>system\nInstr\n<|im_end|>\n\n<|im_start|>user\nQuestion\n<|im_end|>\n<|im_start|>assistant\n",
				promptBuilder.buildFinalPrompt("Instr", "Ctx", "Question"));
	}

	@Test
	void buildFinalPromptKeepsWhitespaceVerbatim() {
		assertEquals(" Ctx <|im_start|>system\n Instr \n<|im_end|>\n\n<|im_start|>user\n Question \n<|im_end|>\n<|im_start|>assistant\n",
				promptBuilder.buildFinalPrompt(" Instr ", " Ctx ", " Question "));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"   ", "\t\n"})
	void buildFinalPromptWithoutInstructions(@Nullable final String instructions) {
		assertEquals("Ctx\n<|im_start|>user\nQuestion\n<|im_end|>\n<|im_start|>assistant\n",
				promptBuilder.buildFinalPrompt(instructions, "Ctx", "Question"));
	}
}
