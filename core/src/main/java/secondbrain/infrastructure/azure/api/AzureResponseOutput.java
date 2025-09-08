package secondbrain.infrastructure.azure.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AzureResponseOutput(List<AzureResponseOutputContent> content) {
    public List<AzureResponseOutputContent> getContent() {
        return Objects.requireNonNullElse(content, List.of());
    }
}
