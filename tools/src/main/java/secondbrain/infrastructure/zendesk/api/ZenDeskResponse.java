package secondbrain.infrastructure.zendesk.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ZenDeskResponse(List<ZenDeskResultsResponse> results, String next_page) {

    public ZenDeskResultsResponse[] getResultsArray() {
        if (results == null) {
            return new ZenDeskResultsResponse[]{};
        }

        return results.toArray(new ZenDeskResultsResponse[0]);
    }
}
