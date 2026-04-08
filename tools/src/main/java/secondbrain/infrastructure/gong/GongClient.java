package secondbrain.infrastructure.gong;

import org.jspecify.annotations.Nullable;
import secondbrain.infrastructure.gong.api.GongCallExtensive;

import java.time.temporal.ChronoUnit;
import java.util.List;

public interface GongClient {
    boolean anyItemsInDuration(
            String company,
            String username,
            String password,
            ChronoUnit duration,
            ChronoUnit cached);

    List<GongCallExtensive> getCallsExtensive(
            String company,
            @Nullable String callId,
            String username,
            String password,
            @Nullable String fromDateTime,
            @Nullable String toDateTime);

    String getCallTranscript(
            String username,
            String password,
            GongCallExtensive call);
}
