package secondbrain.domain.answer;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import secondbrain.domain.constants.ModelRegex;
import secondbrain.domain.test.TestConfigUtil;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AnswerFormatterPhi4}. No regex is configured, so the default from {@link ModelRegex} is used.
 */
@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(AnswerFormatterPhi4.class)
class AnswerFormatterPhi4Test {

	@Inject
	private AnswerFormatterPhi4 formatter;

	@BeforeEach
	void updateConfig() {
		TestConfigUtil.registerConfig(Map.of("sb.infrastructure.mock", "true"));
	}

	@Test
	void removesThinkingTagsAndTheirContent() {
		assertEquals("This is a test  string.", formatter.formatAnswer("This is a test <think>remove this</think> string."));
	}

	@Test
	void removesMultilineThinkingAndTrimsTheResult() {
		assertEquals("The answer is 42.", formatter.formatAnswer("<think>\nline one\nline two\n</think>\n\nThe answer is 42.\n"));
	}

	@Test
	void removesEveryThinkingBlockWithoutRemovingTextBetweenThem() {
		assertEquals("abc", formatter.formatAnswer("a<think>x</think>b<think>y</think>c"));
	}

	@Test
	void removesEverythingUpToTheEndTagWhenTheStartTagIsMissing() {
		assertEquals("string.", formatter.formatAnswer("This is a test remove this\n</think> string."));
	}

	@Test
	void onlyTheFirstUnmatchedEndTagIsUsedWhenTheStartTagIsMissing() {
		assertEquals("answer</think>tail", formatter.formatAnswer("reasoning</think>answer</think>tail"));
	}

	@Test
	void anUnclosedStartTagIsLeftInPlace() {
		assertEquals("<think>still thinking", formatter.formatAnswer("  <think>still thinking  "));
	}

	@Test
	void textWithoutThinkingTagsIsOnlyTrimmed() {
		assertEquals("This is a test string.", formatter.formatAnswer("  This is a test string.\n"));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"   ", "\n\t"})
	void blankInputReturnsAnEmptyString(final String input) {
		assertEquals("", formatter.formatAnswer(input));
	}

	@Test
	void anAnswerThatIsOnlyThinkingBecomesEmpty() {
		assertEquals("", formatter.formatAnswer("<think>nothing to say</think>  "));
	}

	@Test
	void modelRegexDefaultsToTheConstant() {
		assertEquals(ModelRegex.PHI4_REGEX, formatter.modelRegex());
	}

	@ParameterizedTest
	@ValueSource(strings = {"Phi-4", "Phi-4-reasoning", "phi4"})
	void modelRegexMatchesSupportedModels(final String model) {
		assertTrue(model.matches(formatter.modelRegex()), model);
	}

	@ParameterizedTest
	@ValueSource(strings = {"Phi-4-mini", "Phi-4-reasoning-plus", "phi4-mini", "phi4:14b", "phi-4", "myphi4", "nemotron-3-nano", "qwen3"})
	void modelRegexDoesNotMatchOtherModels(final String model) {
		assertFalse(model.matches(formatter.modelRegex()), model);
	}
}
