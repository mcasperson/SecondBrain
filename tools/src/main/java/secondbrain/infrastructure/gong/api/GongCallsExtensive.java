package secondbrain.infrastructure.gong.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GongCallsExtensive(List<GongCallExtensive> calls, GongRecords records) {
    public GongCallExtensive[] getCallsArray() {
        return calls.toArray(new GongCallExtensive[0]);
    }
}
