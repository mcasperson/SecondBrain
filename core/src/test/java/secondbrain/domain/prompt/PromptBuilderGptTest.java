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
@AddBeanClasses(PromptBuilderGpt.class)
class PromptBuilderGptTest {

	@Inject
	private PromptBuilderGpt promptBuilder;

	@ParameterizedTest
	@ValueSource(strings = {"gpt-oss", "gpt-oss:20b", "gpt-oss:120b"})
	void modelRegexMatchesGptOssModels(final String model) {
		assertTrue(model.matches(promptBuilder.modelRegex()));
	}

	@ParameterizedTest
	@ValueSource(strings = {"gpt-4o", "gptoss", "GPT-OSS", "openai/gpt-oss", "mistral"})
	void modelRegexRejectsOtherModels(final String model) {
		assertFalse(model.matches(promptBuilder.modelRegex()));
	}

	@Test
	void buildContextPromptIncludesTitleAndPrompt() {
		assertEquals("<|start|>system<|message|>\nTitle:\nBody\n",
				promptBuilder.buildContextPrompt("Title", "Body"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "   ", "\t\n"})
	void buildContextPromptOmitsBlankTitle(final String title) {
		assertEquals("<|start|>system<|message|>\nBody\n",
				promptBuilder.buildContextPrompt(title, "Body"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "   ", "\t\n"})
	void buildContextPromptReturnsEmptyForBlankPrompt(final String prompt) {
		assertEquals("", promptBuilder.buildContextPrompt("Title", prompt));
	}

	@Test
	void buildFinalPromptWithInstructions() {
		assertEquals("Ctx<|start|>system<|message|>\nInstr\n\n<|start|>user<|message|>\nQuestion\n<|start|>assistant\n",
				promptBuilder.buildFinalPrompt("Instr", "Ctx", "Question"));
	}

	@Test
	void buildFinalPromptKeepsWhitespaceVerbatim() {
		assertEquals(" Ctx <|start|>system<|message|>\n Instr \n\n<|start|>user<|message|>\n Question \n<|start|>assistant\n",
				promptBuilder.buildFinalPrompt(" Instr ", " Ctx ", " Question "));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"   ", "\t\n"})
	void buildFinalPromptWithoutInstructions(@Nullable final String instructions) {
		assertEquals("Ctx\n<|start|>user<|message|>\nQuestion\n<|start|>assistant\n",
				promptBuilder.buildFinalPrompt(instructions, "Ctx", "Question"));
	}
}
