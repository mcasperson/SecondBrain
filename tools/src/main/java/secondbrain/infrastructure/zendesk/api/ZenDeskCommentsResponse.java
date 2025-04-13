package secondbrain.infrastructure.zendesk.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ZenDeskCommentsResponse(List<ZenDeskCommentResponse> comments) {
    public List<ZenDeskCommentResponse> getResults() {
        return Objects.requireNonNullElse(comments, List.of());
    }
}
