package secondbrain.infrastructure.gong;

import jakarta.ws.rs.client.Client;
import secondbrain.domain.tools.gong.model.GongCallDetails;

import java.util.List;

public interface GongClient {
    List<GongCallDetails> getCallsExtensive(
            Client client,
            String company,
            String callId,
            String username,
            String password,
            String fromDateTime,
            String toDateTime);

    String getCallTranscript(
            Client client,
            String username,
            String password,
            String id);
}
