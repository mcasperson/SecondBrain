package secondbrain.infrastructure.zendesk;

import java.util.List;

public record ZenDeskResponse(List<ZenDeskResultsResponse> results) {
}
