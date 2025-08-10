package secondbrain.infrastructure.azure.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AzureResponse(List<AzureResponseChoices> choices) {
    public List<AzureResponseChoices> getChoices() {
        return Objects.requireNonNullElse(choices, List.of());
    }
}
