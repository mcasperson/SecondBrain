package secondbrain.infrastructure.gong;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import secondbrain.domain.injection.Preferred;
import secondbrain.infrastructure.gong.api.GongCallExtensive;
import secondbrain.infrastructure.gong.api.GongCallExtensiveContext;
import secondbrain.infrastructure.gong.api.GongCallExtensiveMetadata;
import secondbrain.infrastructure.gong.api.GongCallExtensiveParty;
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
    public List<GongCallExtensive> getCallsExtensive(final String company, final String callId, final String username, final String password, final String fromDateTime, final String toDateTime) {
        return List.of(new GongCallExtensive(
                new GongCallExtensiveMetadata("12345", "https://gong.io/call/12345", "2023-10-01T10:00:00Z"),
                List.of(new GongCallExtensiveContext("unused", List.of())),
                List.of(new GongCallExtensiveParty("1", "Alice", "Speaker1"))));
    }

    @Override
    public String getCallTranscript(final String username, final String password, final GongCallExtensive call) {
        return llmClient.call("Write a 5 paragraph call log between 3 people discussing the design of a new AI product.");
    }
}
