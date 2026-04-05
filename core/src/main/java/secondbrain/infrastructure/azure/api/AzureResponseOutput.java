package secondbrain.infrastructure.azure.api;

import java.util.List;

public record AzureResponseOutput(List<AzureResponseOutputContent> content, String type) {
}
