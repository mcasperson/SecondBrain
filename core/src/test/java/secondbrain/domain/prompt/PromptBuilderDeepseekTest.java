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
@AddBeanClasses(PromptBuilderDeepseek.class)
class PromptBuilderDeepseekTest {

	/**
	 * DeepSeek's special tokens use FULLWIDTH VERTICAL LINE (U+FF5C) and LOWER ONE EIGHTH BLOCK (U+2581).
	 */
	private static final String USER = "<｜User｜>";
	private static final String END_OF_SENTENCE = "<｜end▁of▁sentence｜>";
	private static final String ASSISTANT = "<｜Assistant｜>";

	@Inject
	private PromptBuilderDeepseek promptBuilder;

	@ParameterizedTest
	@ValueSource(strings = {"deepseek-r1", "deepseek-r1:8b", "deepseek-r1:70b"})
	void modelRegexMatchesDeepseekR1Models(final String model) {
		assertTrue(model.matches(promptBuilder.modelRegex()));
	}

	@ParameterizedTest
	@ValueSource(strings = {"deepseek-v3", "deepseek-coder", "DeepSeek-R1", "hf.co/deepseek-r1", "mistral"})
	void modelRegexRejectsOtherModels(final String model) {
		assertFalse(model.matches(promptBuilder.modelRegex()));
	}

	@Test
	void buildContextPromptIncludesTitleAndPrompt() {
		assertEquals("Title:\nBody", promptBuilder.buildContextPrompt("Title", "Body"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "   ", "\t\n"})
	void buildContextPromptOmitsBlankTitle(final String title) {
		assertEquals("Body", promptBuilder.buildContextPrompt(title, "Body"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "   ", "\t\n"})
	void buildContextPromptReturnsEmptyForBlankPrompt(final String prompt) {
		assertEquals("", promptBuilder.buildContextPrompt("Title", prompt));
	}

	@Test
	void buildFinalPromptWithInstructions() {
		assertEquals("Instr\nCtx\n" + USER + "\nQuestion\n" + END_OF_SENTENCE + "\n" + ASSISTANT,
				promptBuilder.buildFinalPrompt("Instr", "Ctx", "Question"));
	}

	@Test
	void buildFinalPromptKeepsWhitespaceVerbatim() {
		assertEquals(" Instr \n Ctx \n" + USER + "\n Question \n" + END_OF_SENTENCE + "\n" + ASSISTANT,
				promptBuilder.buildFinalPrompt(" Instr ", " Ctx ", " Question "));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"   ", "\t\n"})
	void buildFinalPromptWithoutInstructions(@Nullable final String instructions) {
		assertEquals("Ctx\n" + USER + "\nQuestion\n" + END_OF_SENTENCE + "\n" + ASSISTANT,
				promptBuilder.buildFinalPrompt(instructions, "Ctx", "Question"));
	}
}
