package secondbrain.infrastructure.mock;

import jakarta.enterprise.context.ApplicationScoped;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.infrastructure.llm.LlmClient;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@ApplicationScoped
public class MockLLmCLient implements LlmClient {

    @Nullable
    private String mockResponse;

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
