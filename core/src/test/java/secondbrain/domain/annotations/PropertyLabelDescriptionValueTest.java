package secondbrain.domain.annotations;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PropertyLabelDescriptionValueTest {
	@Test
	void getValueStringReturnsAStringValueUnchanged() {
		final PropertyLabelDescriptionValue value = new PropertyLabelDescriptionValue("Name", "String", "Acme");

		assertEquals("Acme", value.getValueString());
	}

	@Test
	void getValueStringUsesToStringForNonStringValues() {
		assertEquals("42", new PropertyLabelDescriptionValue("Count", "Integer", 42).getValueString());
		assertEquals("true", new PropertyLabelDescriptionValue("Enabled", "Boolean", true).getValueString());
		assertEquals("[a, b]", new PropertyLabelDescriptionValue("Items", "List", List.of("a", "b")).getValueString());
	}

	/**
	 * Passes null on purpose, as callers do when a property has no value.
	 */
	@SuppressWarnings("NullAway")
	@Test
	void getValueStringReturnsAnEmptyStringWhenValueIsNull() {
		final PropertyLabelDescriptionValue value = new PropertyLabelDescriptionValue("Name", "String", null);

		assertEquals("", value.getValueString());
	}

	@Test
	void getValueStringReturnsAnEmptyStringValueUnchanged() {
		assertEquals("", new PropertyLabelDescriptionValue("Name", "String", "").getValueString());
	}
}
