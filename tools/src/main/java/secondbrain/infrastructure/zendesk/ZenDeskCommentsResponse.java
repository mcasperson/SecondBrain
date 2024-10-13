package secondbrain.infrastructure.zendesk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ZenDeskCommentsResponse(List<ZenDeskCommentResponse> results) {
    public List<ZenDeskCommentResponse> getResults() {
        return Objects.requireNonNullElse(results, List.of());
    }
}
