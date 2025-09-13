package secondbrain.infrastructure.azure.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AzureResponseChoice(AzureResponseOutputContent message) {
    public AzureResponseOutputContent getMessage() {
        return Objects.requireNonNullElse(message, new AzureResponseOutputContent(""));
    }
}
