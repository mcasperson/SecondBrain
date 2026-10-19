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
@AddBeanClasses(PromptBuilderGranite.class)
class PromptBuilderGraniteTest {

	@Inject
	private PromptBuilderGranite promptBuilder;

	@ParameterizedTest
	@ValueSource(strings = {"granite4", "granite4:3b", "granite4:micro-h"})
	void modelRegexMatchesGranite4Models(final String model) {
		assertTrue(model.matches(promptBuilder.modelRegex()));
	}

	@ParameterizedTest
	@ValueSource(strings = {"granite3.3", "granite-code", "Granite4", "ibm/granite4", "mistral"})
	void modelRegexRejectsOtherModels(final String model) {
		assertFalse(model.matches(promptBuilder.modelRegex()));
	}

	@Test
	void buildContextPromptIncludesTitleAndPrompt() {
		assertEquals("<|start_of_role|>system<|end_of_role|>\nTitle:\nBody\n<|end_of_text|>",
				promptBuilder.buildContextPrompt("Title", "Body"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "   ", "\t\n"})
	void buildContextPromptOmitsBlankTitle(final String title) {
		assertEquals("<|start_of_role|>system<|end_of_role|>\nBody\n<|end_of_text|>",
				promptBuilder.buildContextPrompt(title, "Body"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "   ", "\t\n"})
	void buildContextPromptReturnsEmptyForBlankPrompt(final String prompt) {
		assertEquals("", promptBuilder.buildContextPrompt("Title", prompt));
	}

	@Test
	void buildFinalPromptWithInstructions() {
		assertEquals("Ctx<|start_of_role|>system<|end_of_role|>\nInstr\n<|end_of_text|>\n"
						+ "\n<|start_of_role|>user<|end_of_role|>\nQuestion\n<|end_of_text|>\n"
						+ "<|start_of_role|>assistant<|end_of_role|>\n",
				promptBuilder.buildFinalPrompt("Instr", "Ctx", "Question"));
	}

	@Test
	void buildFinalPromptKeepsWhitespaceVerbatim() {
		assertEquals(" Ctx <|start_of_role|>system<|end_of_role|>\n Instr \n<|end_of_text|>\n"
						+ "\n<|start_of_role|>user<|end_of_role|>\n Question \n<|end_of_text|>\n"
						+ "<|start_of_role|>assistant<|end_of_role|>\n",
				promptBuilder.buildFinalPrompt(" Instr ", " Ctx ", " Question "));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"   ", "\t\n"})
	void buildFinalPromptWithoutInstructions(@Nullable final String instructions) {
		assertEquals("Ctx\n<|start_of_role|>user<|end_of_role|>\nQuestion\n<|end_of_text|>\n"
						+ "<|start_of_role|>assistant<|end_of_role|>\n",
				promptBuilder.buildFinalPrompt(instructions, "Ctx", "Question"));
	}
}
