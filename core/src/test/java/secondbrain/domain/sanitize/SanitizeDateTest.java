package secondbrain.domain.sanitize;

import io.smallrye.common.annotation.Identifier;
import io.smallrye.config.inject.ConfigExtension;
import jakarta.inject.Inject;
import org.jboss.weld.junit5.auto.AddBeanClasses;
import org.jboss.weld.junit5.auto.AddExtensions;
import org.jboss.weld.junit5.auto.EnableAutoWeld;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EnableAutoWeld
@AddExtensions(ConfigExtension.class)
@AddBeanClasses(SanitizeDate.class)
class SanitizeDateTest {
	@Inject
	@Identifier("sanitizeDate")
	private SanitizeArgument sanitizeDate;

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "\t\n "})
	void blankArgumentsReturnAnEmptyString(final String argument) {
		assertEquals("", sanitizeDate.sanitize(argument, "document"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"now", "NOW", "Now", "  now  ", "\tnow\n"})
	void theHallucinatedValueNowIsRemovedIgnoringCaseAndSurroundingWhitespace(final String argument) {
		assertEquals("", sanitizeDate.sanitize(argument, "document"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"2024-01-31", "nowhere", "right now", "now2024", "yesterday"})
	void otherValuesAreReturnedUnchanged(final String argument) {
		assertEquals(argument, sanitizeDate.sanitize(argument, "document"));
	}

	/**
	 * Surrounding whitespace is only trimmed for the comparison; a valid date is returned exactly as supplied.
	 */
	@Test
	void validDatesKeepTheirOriginalWhitespace() {
		assertEquals(" 2024-01-31 ", sanitizeDate.sanitize(" 2024-01-31 ", "document"));
	}

	@Test
	void theDocumentIsIgnored() {
		assertEquals("2024-01-31", sanitizeDate.sanitize("2024-01-31", null));
		assertEquals("", sanitizeDate.sanitize("now", "the date is 2024-01-31"));
	}
}
