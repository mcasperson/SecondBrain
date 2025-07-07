package secondbrain.infrastructure.zendesk.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ZenDeskCommentsResponse(List<ZenDeskCommentResponse> comments) {
    public List<ZenDeskCommentResponse> getResults() {
        return Objects.requireNonNullElse(comments, List.of());
    }

    public ZenDeskProcessedCommentsResponse toProcessedCommentsResponse(final IdToString authorIdToName) {
        return new ZenDeskProcessedCommentsResponse(
                getResults()
                        .stream()
                        .map(comment -> new ZenDeskProcessedCommentResponse(
                                comment.body(),
                                authorIdToName.getStringFromId(comment.author_id())))
                        .collect(Collectors.toList())
        );
    }
}
