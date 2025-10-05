package secondbrain.infrastructure.azure.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AzureResponseChoice(AzureResponseOutputContent message,
                                  @JsonProperty("finish_reason") String finishReason) {
    public AzureResponseOutputContent getMessage() {
        return Objects.requireNonNullElse(message, new AzureResponseOutputContent(""));
    }
}
