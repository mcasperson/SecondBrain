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
@AddBeanClasses(PromptBuilderLlama3.class)
class PromptBuilderLlama3Test {

	@Inject
	private PromptBuilderLlama3 promptBuilder;

	@ParameterizedTest
	@ValueSource(strings = {"llama3", "llama3.1", "llama3.2:3b", "llama3.3:70b"})
	void modelRegexMatchesLlama3Models(final String model) {
		assertTrue(model.matches(promptBuilder.modelRegex()));
	}

	@ParameterizedTest
	@ValueSource(strings = {"llama2", "llama", "codellama3", "Llama3", "mistral"})
	void modelRegexRejectsOtherModels(final String model) {
		assertFalse(model.matches(promptBuilder.modelRegex()));
	}

	@Test
	void buildContextPromptIncludesTitleAndPrompt() {
		assertEquals("<|start_header_id|>system<|end_header_id|>\nTitle:\nBody\n<|eot_id|>",
				promptBuilder.buildContextPrompt("Title", "Body"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "   ", "\t\n"})
	void buildContextPromptOmitsBlankTitle(final String title) {
		assertEquals("<|start_header_id|>system<|end_header_id|>\nBody\n<|eot_id|>",
				promptBuilder.buildContextPrompt(title, "Body"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "   ", "\t\n"})
	void buildContextPromptReturnsEmptyForBlankPrompt(final String prompt) {
		assertEquals("", promptBuilder.buildContextPrompt("Title", prompt));
	}

	@Test
	void buildFinalPromptWithInstructions() {
		assertEquals("<|begin_of_text|>\n"
						+ "<|start_header_id|>system<|end_header_id|>\nInstr\n<|eot_id|>\n"
						+ "Ctx\n<|start_header_id|>user<|end_header_id|>\nQuestion\n<|eot_id|>\n"
						+ "<|start_header_id|>assistant<|end_header_id|>",
				promptBuilder.buildFinalPrompt("Instr", "Ctx", "Question"));
	}

	@Test
	void buildFinalPromptKeepsWhitespaceVerbatim() {
		assertEquals("<|begin_of_text|>\n"
						+ "<|start_header_id|>system<|end_header_id|>\n Instr \n<|eot_id|>\n"
						+ " Ctx \n<|start_header_id|>user<|end_header_id|>\n Question \n<|eot_id|>\n"
						+ "<|start_header_id|>assistant<|end_header_id|>",
				promptBuilder.buildFinalPrompt(" Instr ", " Ctx ", " Question "));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"   ", "\t\n"})
	void buildFinalPromptWithoutInstructions(@Nullable final String instructions) {
		assertEquals("<|begin_of_text|>\n"
						+ "Ctx\n<|start_header_id|>user<|end_header_id|>\nQuestion\n<|eot_id|>\n"
						+ "<|start_header_id|>assistant<|end_header_id|>",
				promptBuilder.buildFinalPrompt(instructions, "Ctx", "Question"));
	}
}
