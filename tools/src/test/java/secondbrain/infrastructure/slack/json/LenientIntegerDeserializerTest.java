package secondbrain.infrastructure.slack.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class LenientIntegerDeserializerTest {
	private final ObjectMapper mapper = new ObjectMapper();
	private final LenientIntegerDeserializer deserializer = new LenientIntegerDeserializer();

	/**
	 * Deserializes the first JSON value directly, so tokens that Jackson would normally handle itself (such as null)
	 * reach the deserializer.
	 */
	@Nullable
	private Integer deserialize(final JsonParser parser) throws IOException {
		parser.nextToken();
		return deserializer.deserialize(parser, mapper.getDeserializationContext());
	}

	@ParameterizedTest
	@CsvSource({
			"42, 42",
			"0, 0",
			"-7, -7",
			"2147483647, 2147483647",
			"3.9, 3"
	})
	void numbersAreReadAsIntegers(final String json, final int expected) throws IOException {
		try (JsonParser parser = mapper.createParser(json)) {
			assertEquals(expected, deserialize(parser));
		}
	}

	@ParameterizedTest
	@CsvSource({
			"'\"17\"', 17",
			"'\"-3\"', -3",
			"'\"0\"', 0"
	})
	void numericStringsAreParsed(final String json, final int expected) throws IOException {
		try (JsonParser parser = mapper.createParser(json)) {
			assertEquals(expected, deserialize(parser));
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"\"\"", "\"abc\"", "\" 5\"", "\"1.5\"", "\"2147483648\"", "null"})
	void blankInvalidOrNullValuesAreNull(final String json) throws IOException {
		try (JsonParser parser = mapper.createParser(json)) {
			assertNull(deserialize(parser));
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"{\"$numberInt\": \"5\", \"nested\": {\"a\": 1}} 9", "[1, [2, 3]] 9"})
	void objectsAndArraysAreSkippedAndAreNull(final String json) throws IOException {
		try (JsonParser parser = mapper.createParser(json)) {
			assertNull(deserialize(parser));

			// The whole structure was consumed, so the next token is the value that follows it
			assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
			assertEquals(9, parser.getIntValue());
		}
	}

	@Test
	void fieldsAfterAnObjectValueAreStillRead() throws IOException {
		final Holder holder = mapper.readValue("{\"count\": {\"$numberInt\": \"5\"}, \"name\": \"after\"}", Holder.class);

		assertNull(holder.count());
		assertEquals("after", holder.name());
	}

	@Test
	void annotatedFieldsReadStringsAndNumbers() throws IOException {
		assertEquals(12, mapper.readValue("{\"count\": \"12\", \"name\": \"n\"}", Holder.class).count());
		assertEquals(13, mapper.readValue("{\"count\": 13, \"name\": \"n\"}", Holder.class).count());
	}

	private record Holder(@JsonDeserialize(using = LenientIntegerDeserializer.class) @Nullable Integer count,
						  @Nullable String name) {
	}
}
