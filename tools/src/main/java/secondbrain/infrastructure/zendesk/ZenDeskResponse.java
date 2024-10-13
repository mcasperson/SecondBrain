package secondbrain.infrastructure.zendesk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ZenDeskResponse(List<ZenDeskResultsResponse> results) {
    public List<ZenDeskResultsResponse> getResults() {
        return Objects.requireNonNullElse(results, List.of());
    }
}
