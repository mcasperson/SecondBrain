package secondbrain.infrastructure.azure.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AzureResponse(List<AzureResponseOutput> output, AzureResponseError error) {
    public List<AzureResponseOutput> getChoices() {
        return Objects.requireNonNullElse(output, List.of());
    }
}
