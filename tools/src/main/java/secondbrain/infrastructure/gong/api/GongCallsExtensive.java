package secondbrain.infrastructure.gong.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GongCallsExtensive(List<GongCallExtensive> calls, GongRecords records) {
    public List<GongCallExtensive> getCalls() {
        return Objects.requireNonNullElse(calls, List.of());
    }

    public GongRecords getRecords() {
        return Objects.requireNonNullElse(records, new GongRecords(0, 0, 0, ""));
    }

    public GongCallExtensive[] getCallsArray() {
        return getCalls().toArray(new GongCallExtensive[0]);
    }
}
