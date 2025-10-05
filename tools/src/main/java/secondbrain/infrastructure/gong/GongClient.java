package secondbrain.infrastructure.gong;

import secondbrain.infrastructure.gong.api.GongCallExtensive;

import java.util.List;

public interface GongClient {
    List<GongCallExtensive> getCallsExtensive(
            String company,
            String callId,
            String username,
            String password,
            String fromDateTime,
            String toDateTime);

    String getCallTranscript(
            String username,
            String password,
            GongCallExtensive call);
}
