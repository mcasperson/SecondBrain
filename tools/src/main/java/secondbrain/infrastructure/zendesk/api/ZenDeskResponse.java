package secondbrain.infrastructure.zendesk.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ZenDeskResponse(List<ZenDeskTicket> results, String next_page) {

    public ZenDeskTicket[] getResultsArray() {
        if (results == null) {
            return new ZenDeskTicket[]{};
        }

        return results.toArray(new ZenDeskTicket[0]);
    }
}
