package secondbrain.infrastructure.zendesk;

import java.util.List;

public record ZenDeskCommentsResponse(List<ZenDeskCommentResponse> results) {
}
