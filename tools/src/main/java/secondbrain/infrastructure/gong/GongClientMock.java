package secondbrain.infrastructure.gong;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import secondbrain.domain.config.ModelConfig;
import secondbrain.domain.context.RagMultiDocumentContext;
import secondbrain.domain.prompt.PromptBuilderSelector;
import secondbrain.domain.tools.gong.model.GongCallDetails;
import secondbrain.infrastructure.ollama.OllamaClient;

import java.util.List;

/**
 * A mock implementation of the GongClient interface that uses Ollama to generate a random call transcript.
 */
@ApplicationScoped
public class GongClientMock implements GongClient {
    @Inject
    private OllamaClient ollamaClient;

    @Inject
    private ModelConfig modelConfig;

    @Inject
    private PromptBuilderSelector promptBuilderSelector;

    @Override
    public List<GongCallDetails> getCallsExtensive(final Client client, final String company, final String callId, final String username, final String password, final String fromDateTime, final String toDateTime) {
        return List.of(new GongCallDetails("123456", "https://example.com/call/123456", List.of()));
    }

    @Override
    public String getCallTranscript(final Client client, final String username, final String password, final GongCallDetails call) {
        return ollamaClient.callOllama(new RagMultiDocumentContext<Void>(
                                promptBuilderSelector.getPromptBuilder(modelConfig.getModel())
                                        .buildFinalPrompt("", "", "Write a 5 paragraph call log between 3 people discussing the design of a new AI product.")),
                        modelConfig.getModel(),
                        2048)
                .combinedDocument();
    }
}
