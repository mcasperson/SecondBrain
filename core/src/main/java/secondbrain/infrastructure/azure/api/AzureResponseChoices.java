package secondbrain.infrastructure.azure.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AzureResponseChoices(AzureResponseChoicesMessage message) {
    public AzureResponseChoicesMessage getMessage() {
        return Objects.requireNonNullElse(message, new AzureResponseChoicesMessage(""));
    }
}
