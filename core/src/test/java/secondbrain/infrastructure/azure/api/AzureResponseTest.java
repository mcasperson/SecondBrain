package secondbrain.infrastructure.azure.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Passes null on purpose throughout, as Jackson does when the JSON omits a property.
 */
@SuppressWarnings("NullAway")
class AzureResponseTest {
	private final ObjectMapper objectMapper = new ObjectMapper();

	private static AzureResponse response(final List<AzureResponseChoice> choices, final List<AzureResponseOutput> output) {
		return new AzureResponse(choices, output, null, null, 1L, "id", "model", "object");
	}

	private static AzureResponseChoice choice(final String content) {
		return new AzureResponseChoice(new AzureResponseChoiceMessage(content), "stop");
	}

	@Test
	void getChoicesReturnsTheSuppliedList() {
		final List<AzureResponseChoice> choices = List.of(choice("a"));

		assertSame(choices, response(choices, null).getChoices());
	}

	@Test
	void getChoicesReturnsAnEmptyListWhenChoicesIsNull() {
		assertEquals(List.of(), response(null, null).getChoices());
	}

	@Test
	void getOutputReturnsTheSuppliedList() {
		final List<AzureResponseOutput> output = List.of(new AzureResponseOutput(List.of(), "text", "message"));

		assertSame(output, response(null, output).getOutput());
	}

	@Test
	void getOutputReturnsAnEmptyListWhenOutputIsNull() {
		assertEquals(List.of(), response(null, null).getOutput());
	}

	@Test
	void getResponseTextConcatenatesChoiceContentsInOrder() {
		final AzureResponse response = response(List.of(choice("Hello, "), choice("world")), null);

		assertEquals("Hello, world", response.getResponseText());
	}

	@Test
	void getResponseTextTreatsAChoiceWithoutAMessageAsEmpty() {
		final AzureResponse response = response(
				List.of(choice("first"), new AzureResponseChoice(null, "length"), choice(null), choice("last")),
				null);

		assertEquals("firstlast", response.getResponseText());
	}

	@Test
	void getResponseTextPrefersChoicesOverOutput() {
		final AzureResponse response = response(
				List.of(choice("from choices")),
				List.of(new AzureResponseOutput(
						List.of(new AzureResponseOutputContent("from content", "output_text")),
						"from text",
						"message")));

		assertEquals("from choices", response.getResponseText());
	}

	@Test
	void getResponseTextUsesOutputTextWhenThereAreNoChoices() {
		final AzureResponse response = response(
				List.of(),
				List.of(
						new AzureResponseOutput(
								List.of(new AzureResponseOutputContent("from content", "output_text")),
								"first ",
								"message"),
						new AzureResponseOutput(List.of(), null, "reasoning"),
						new AzureResponseOutput(List.of(), "second", "message")));

		assertEquals("first second", response.getResponseText());
	}

	@Test
	void getResponseTextFallsBackToOutputTextContentWhenAllOutputTextIsNull() {
		final AzureResponse response = response(
				null,
				List.of(
						new AzureResponseOutput(
								List.of(
										new AzureResponseOutputContent("Hello, ", "output_text"),
										new AzureResponseOutputContent("ignored", "refusal"),
										new AzureResponseOutputContent("ignored", null)),
								null,
								"message"),
						new AzureResponseOutput(null, null, "reasoning"),
						new AzureResponseOutput(
								List.of(new AzureResponseOutputContent("world", "output_text")),
								null,
								"message")));

		assertEquals("Hello, world", response.getResponseText());
	}

	@Test
	void getResponseTextFallsBackToContentWhenOutputTextIsBlank() {
		final AzureResponse response = response(
				null,
				List.of(new AzureResponseOutput(
						List.of(new AzureResponseOutputContent("content text", "output_text")),
						"  ",
						"message")));

		assertEquals("content text", response.getResponseText());
	}

	@Test
	void getResponseTextReturnsAnEmptyStringWhenOutputHasNoTextOrOutputTextContent() {
		final AzureResponse response = response(
				null,
				List.of(new AzureResponseOutput(
						List.of(new AzureResponseOutputContent("ignored", "refusal")),
						null,
						"message")));

		assertEquals("", response.getResponseText());
	}

	@Test
	void getResponseTextReturnsAnEmptyStringWhenThereAreNoChoicesOrOutput() {
		assertEquals("", response(null, null).getResponseText());
		assertEquals("", response(List.of(), List.of()).getResponseText());
	}

	@Test
	void getResponseTextReadsTheResponsesApiFormat() throws Exception {
		final AzureResponse response = objectMapper.readValue("""
				{"id":"resp_1","object":"response","created":1700000000,"model":"gpt","status":"completed",
				 "output":[
				  {"type":"reasoning","content":[]},
				  {"type":"message","content":[{"type":"output_text","text":"answer","annotations":[]}]}
				 ]}
				""", AzureResponse.class);

		assertEquals(List.of(), response.getChoices());
		assertEquals(2, response.getOutput().size());
		assertEquals("answer", response.getResponseText());
	}

	@Test
	void getResponseTextReadsTheChatCompletionsFormat() throws Exception {
		final AzureResponse response = objectMapper.readValue("""
				{"id":"chatcmpl-1","object":"chat.completion","created":1700000000,"model":"gpt",
				 "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"chat answer"}}]}
				""", AzureResponse.class);

		assertEquals(List.of(), response.getOutput());
		assertEquals("chat answer", response.getResponseText());
	}
}
