package secondbrain.infrastructure.azure.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AzureResponseOutput(AzureResponseOutputMessage message) {
    public AzureResponseOutputMessage getMessage() {
        return Objects.requireNonNullElse(message, new AzureResponseOutputMessage(""));
    }
}
