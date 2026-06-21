package secondbrain.infrastructure.mock;

import jakarta.enterprise.context.ApplicationScoped;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.infrastructure.llm.LlmClient;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A mock implementation of the {@link LlmClient} interface used for testing and simulation purposes.
 * This class enables controlled responses to LLM calls by allowing the user to set a predefined mock response.
 * If no mock response is set, it defaults to an empty string.
 * <p>
 * This implementation is not exposed by LlmClientProducer. It is only intended to be used by tests.
 */
@ApplicationScoped
public class MockLLmCLient implements LlmClient {

    @Nullable
    private String mockResponse;

    /**
     * Sets the predefined mock response to be returned by the {@code MockLLmClient}.
     *
     * @param response the mock response to be used. It can be {@code null}, in which case
     *                 the default behavior will use an empty string.
     */
    public void setMockResponse(@Nullable final String response) {
        this.mockResponse = response;
    }

    @Override
    public String call(final String prompt, final Map<String, String> environmentSettings) {
        return Objects.requireNonNullElse(mockResponse, "");
    }

    @Override
    public String call(final String prompt, final String model, final Map<String, String> environmentSettings) {
        return Objects.requireNonNullElse(mockResponse, "");
    }

    @Override
    public <T> RagMultiDocumentContext<T> callWithCache(final RagMultiDocumentContext<T> ragDoc, final Map<String, String> environmentSettings, final String tool) {
        final List<String> responses = ragDoc.getPrompts().stream()
                .map(prompt -> Objects.requireNonNullElse(mockResponse, ""))
                .toList();
        return ragDoc.updateResponses(responses);
    }
}
