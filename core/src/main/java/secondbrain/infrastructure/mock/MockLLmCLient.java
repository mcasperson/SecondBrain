package secondbrain.infrastructure.mock;

import jakarta.enterprise.context.ApplicationScoped;
import org.jspecify.annotations.Nullable;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.infrastructure.llm.LlmClient;

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
    public String call(final String prompt) {
        return Objects.requireNonNullElse(mockResponse, "");
    }

    @Override
    public String call(final String prompt, final String model) {
        return Objects.requireNonNullElse(mockResponse, "");
    }

    @Override
    public <T> RagMultiDocumentContext<T> callWithCache(final RagMultiDocumentContext<T> ragDoc, final Map<String, String> environmentSettings, final String tool) {
        return new RagMultiDocumentContext<T>(
                ragDoc.prompt(),
                ragDoc.instructions(),
                ragDoc.individualContexts(),
                mockResponse,
                "debug",
                "",
                null
        );
    }
}
