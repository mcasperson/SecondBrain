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
@AddBeanClasses(PromptBuilderMistral.class)
class PromptBuilderMistralTest {

	@Inject
	private PromptBuilderMistral promptBuilder;

	@ParameterizedTest
	@ValueSource(strings = {"mistral", "mistral:7b", "mistral-nemo", "mixtral:8x7b", "ministral-3:8b"})
	void modelRegexMatchesMistralFamilyModels(final String model) {
		assertTrue(model.matches(promptBuilder.modelRegex()));
	}

	@ParameterizedTest
	@ValueSource(strings = {"Mistral", "my-mistral", "mstral", "llama3", "gemma3"})
	void modelRegexRejectsOtherModels(final String model) {
		assertFalse(model.matches(promptBuilder.modelRegex()));
	}

	@Test
	void buildContextPromptIncludesTitleAndPrompt() {
		assertEquals("---------------------\nTitle:\nBody\n---------------------",
				promptBuilder.buildContextPrompt("Title", "Body"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "   ", "\t\n"})
	void buildContextPromptOmitsBlankTitle(final String title) {
		assertEquals("---------------------\nBody\n---------------------",
				promptBuilder.buildContextPrompt(title, "Body"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "   ", "\t\n"})
	void buildContextPromptReturnsEmptyForBlankPrompt(final String prompt) {
		assertEquals("", promptBuilder.buildContextPrompt("Title", prompt));
	}

	@Test
	void buildContextPromptDoesNotTrimPrompt() {
		assertEquals("---------------------\nTitle:\n  Body \n---------------------",
				promptBuilder.buildContextPrompt("Title", "  Body "));
	}

	@Test
	void buildFinalPromptWithInstructions() {
		assertEquals("[INST]Instr\nCtx\nQuestion[/INST]",
				promptBuilder.buildFinalPrompt("Instr", "Ctx", "Question"));
	}

	@Test
	void buildFinalPromptTrimsEachPart() {
		assertEquals("[INST]Instr\nCtx\nQuestion[/INST]",
				promptBuilder.buildFinalPrompt("  Instr \n", "\nCtx  ", "\tQuestion "));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"   ", "\t\n"})
	void buildFinalPromptWithoutInstructions(@Nullable final String instructions) {
		assertEquals("[INST]Ctx\nQuestion[/INST]",
				promptBuilder.buildFinalPrompt(instructions, " Ctx\n", "Question "));
	}
}
