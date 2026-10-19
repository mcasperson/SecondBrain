package secondbrain.infrastructure.azure.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AzureResponseOutputTest {
	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void getContentReturnsTheSuppliedContent() {
		final List<AzureResponseOutputContent> content = List.of(
				new AzureResponseOutputContent("first", "output_text"),
				new AzureResponseOutputContent("second", "output_text"));

		final AzureResponseOutput output = new AzureResponseOutput(content, "text", "message");

		assertSame(content, output.getContent());
		assertEquals("text", output.text());
		assertEquals("message", output.type());
	}

	/**
	 * Passes null on purpose, as Jackson does when the JSON has no content property.
	 */
	@SuppressWarnings("NullAway")
	@Test
	void getContentReturnsAnEmptyListWhenContentIsNull() {
		final AzureResponseOutput output = new AzureResponseOutput(null, "text", "reasoning");

		assertEquals(List.of(), output.getContent());
		assertNull(output.content());
	}

	@Test
	void getContentReturnsAnEmptySuppliedList() {
		final List<AzureResponseOutputContent> content = List.of();

		assertSame(content, new AzureResponseOutput(content, "text", "message").getContent());
	}

	@Test
	void deserializesContentAndIgnoresUnknownProperties() throws Exception {
		final AzureResponseOutput output = objectMapper.readValue("""
				{"id":"msg_1","type":"message","text":"summary","status":"completed",
				 "content":[{"type":"output_text","text":"hello","annotations":[]}]}
				""", AzureResponseOutput.class);

		assertEquals("message", output.type());
		assertEquals("summary", output.text());
		assertEquals(List.of(new AzureResponseOutputContent("hello", "output_text")), output.getContent());
	}

	@Test
	void deserializedOutputWithoutContentHasAnEmptyContentList() throws Exception {
		final AzureResponseOutput output = objectMapper.readValue("{\"type\":\"reasoning\"}", AzureResponseOutput.class);

		assertEquals("reasoning", output.type());
		assertNull(output.text());
		assertEquals(List.of(), output.getContent());
	}
}
