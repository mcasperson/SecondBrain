package secondbrain.infrastructure.gong;

import secondbrain.domain.tools.gong.model.GongCallDetails;

import java.util.List;

public interface GongClient {
    List<GongCallDetails> getCallsExtensive(
            String company,
            String callId,
            String username,
            String password,
            String fromDateTime,
            String toDateTime);

    String getCallTranscript(
            String username,
            String password,
            GongCallDetails call);
}
