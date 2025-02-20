package secondbrain.infrastructure.zendesk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ZenDeskResponse(ZenDeskResultsResponse[] results, String next_page) {
    public ZenDeskResultsResponse[] getResults() {
        return Objects.requireNonNullElse(results, new ZenDeskResultsResponse[]{});
    }
}
