package secondbrain.infrastructure.azure.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AzureResponseOutputContent(String text) {
    public String getText() {
        return Objects.requireNonNullElse(text, "");
    }
}
