package secondbrain.infrastructure.gong;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import secondbrain.domain.injection.Preferred;
import secondbrain.domain.tools.gong.model.GongCallDetails;
import secondbrain.infrastructure.llm.LlmClient;

import java.util.List;

/**
 * A mock implementation of the GongClient interface that uses Ollama to generate a random call transcript.
 */
@ApplicationScoped
public class GongClientMock implements GongClient {
    @Inject
    @Preferred
    private LlmClient llmClient;

    @Override
    public List<GongCallDetails> getCallsExtensive(final Client client, final String company, final String callId, final String username, final String password, final String fromDateTime, final String toDateTime) {
        return List.of(new GongCallDetails("123456", "https://example.com/call/123456", List.of()));
    }

    @Override
    public String getCallTranscript(final Client client, final String username, final String password, final GongCallDetails call) {
        return llmClient.call("Write a 5 paragraph call log between 3 people discussing the design of a new AI product.");
    }
}
