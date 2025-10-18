package secondbrain.infrastructure.mock;

import jakarta.enterprise.context.ApplicationScoped;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.infrastructure.llm.LlmClient;

import java.util.Map;

@ApplicationScoped
public class MockLLmCLient implements LlmClient {

    private String mockResponse;

    public void setMockResponse(final String response) {
        this.mockResponse = response;
    }

    @Override
    public String call(String prompt) {
        return mockResponse;
    }

    @Override
    public String call(String prompt, String model) {
        return mockResponse;
    }

    @Override
    public <T> RagMultiDocumentContext<T> callWithCache(RagMultiDocumentContext<T> ragDoc, Map<String, String> environmentSettings, String tool) {
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
