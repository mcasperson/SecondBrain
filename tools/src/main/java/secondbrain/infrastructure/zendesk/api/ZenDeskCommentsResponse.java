package secondbrain.infrastructure.zendesk.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ZenDeskCommentsResponse(List<ZenDeskCommentResponse> comments) {
    public List<ZenDeskCommentResponse> getResults() {
        return Objects.requireNonNullElse(comments, List.of());
    }

    /**
     * Combine all the responses into a single string, with each response separated by a newline.
     *
     * @param limit The maximum number of comments to include in the body.
     * @return A string representing all the ticket comments.
     */
    public List<String> ticketToBody(final int limit) {
        return getResults()
                .stream()
                .limit(limit)
                .map(ZenDeskCommentResponse::body)
                .map(body -> Arrays.stream(body.split("\\r\\n|\\r|\\n"))
                        .filter(StringUtils::isNotBlank)
                        .collect(Collectors.joining("\n")))
                .collect(Collectors.toList());
    }
}
