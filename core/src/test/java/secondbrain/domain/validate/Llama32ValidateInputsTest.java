package secondbrain.domain.validate;

import io.smallrye.config.inject.ConfigExtension;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(Llama32ValidateInputs.class)
class Llama32ValidateInputsTest {

	@Inject
	private Llama32ValidateInputs validateInputs;

	@ParameterizedTest
	@ValueSource(strings = {"", " ", "\t\n"})
	void blankInputReturnsEmpty(final String input) {
		assertEquals("", validateInputs.getCommaSeparatedList(input));
	}

	@Test
	void emptyListLiteralReturnsEmpty() {
		assertEquals("", validateInputs.getCommaSeparatedList("[]"));
	}

	@Test
	void nullLiteralReturnsEmpty() {
		assertEquals("", validateInputs.getCommaSeparatedList("null"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"alice", "alice,bob", " alice , bob ", "[alice]", "[ ]", "nullable"})
	void otherInputIsReturnedUnchanged(final String input) {
		assertEquals(input, validateInputs.getCommaSeparatedList(input));
	}

	/**
	 * Documents current behaviour: the sentinel checks are exact, case-sensitive and untrimmed,
	 * so padded or differently cased sentinels are passed through unchanged.
	 */
	@ParameterizedTest
	@ValueSource(strings = {" []", "[] ", "NULL", "Null", " null"})
	void sentinelChecksAreExactAndCaseSensitive(final String input) {
		assertEquals(input, validateInputs.getCommaSeparatedList(input));
	}

	@Test
	void withPromptReturnsArgumentContainedInPrompt() {
		assertEquals("alice,bob", validateInputs.getCommaSeparatedList("Find alice,bob in the CRM", "alice,bob"));
	}

	@Test
	void withPromptReturnsEmptyWhenArgumentNotInPrompt() {
		assertEquals("", validateInputs.getCommaSeparatedList("Find alice in the CRM", "carol"));
	}

	@Test
	void withPromptMatchIsCaseSensitive() {
		assertEquals("", validateInputs.getCommaSeparatedList("Find Alice in the CRM", "alice"));
	}

	/**
	 * Documents current behaviour: the prompt check is a substring match, so a fragment of a word in the prompt
	 * is accepted.
	 */
	@Test
	void withPromptAcceptsSubstringOfPrompt() {
		assertEquals("Ali", validateInputs.getCommaSeparatedList("Find Alice in the CRM", "Ali"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "  ", "[]", "null"})
	void withPromptReturnsEmptyForSentinelInputsEvenWhenPromptContainsThem(final String input) {
		assertEquals("", validateInputs.getCommaSeparatedList("Find [] or null or nothing", input));
	}

	@Test
	void withEmptyPromptRejectsNonEmptyArgument() {
		assertEquals("", validateInputs.getCommaSeparatedList("", "alice"));
	}
}
