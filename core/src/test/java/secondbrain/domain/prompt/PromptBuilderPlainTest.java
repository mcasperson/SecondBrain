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
@AddBeanClasses(PromptBuilderPlain.class)
class PromptBuilderPlainTest {

	@Inject
	private PromptBuilderPlain promptBuilder;

	@Test
	void modelRegexMatchesOnlyExactPlain() {
		assertTrue("plain".matches(promptBuilder.modelRegex()));
	}

	@ParameterizedTest
	@ValueSource(strings = {"Plain", "plain2", "plaintext", " plain", "explain", "llama3"})
	void modelRegexRejectsOtherModels(final String model) {
		assertFalse(model.matches(promptBuilder.modelRegex()));
	}

	@Test
	void buildContextPromptIncludesTitleAndPrompt() {
		assertEquals("Title:\nBody\n", promptBuilder.buildContextPrompt("Title", "Body"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "   ", "\t\n"})
	void buildContextPromptOmitsBlankTitle(final String title) {
		assertEquals("Body\n", promptBuilder.buildContextPrompt(title, "Body"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "   ", "\t\n"})
	void buildContextPromptReturnsEmptyForBlankPrompt(final String prompt) {
		assertEquals("", promptBuilder.buildContextPrompt("Title", prompt));
	}

	@Test
	void buildFinalPromptWithInstructions() {
		assertEquals("Instr\nCtx\nQuestion", promptBuilder.buildFinalPrompt("Instr", "Ctx", "Question"));
	}

	@Test
	void buildFinalPromptKeepsWhitespaceVerbatim() {
		assertEquals(" Instr \n Ctx \n Question ", promptBuilder.buildFinalPrompt(" Instr ", " Ctx ", " Question "));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"   ", "\t\n"})
	void buildFinalPromptWithoutInstructions(@Nullable final String instructions) {
		assertEquals("Ctx\nQuestion", promptBuilder.buildFinalPrompt(instructions, "Ctx", "Question"));
	}
}
