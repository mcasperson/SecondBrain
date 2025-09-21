package secondbrain.infrastructure.azure.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AzureResponse(List<AzureResponseChoice> choices, AzureResponseError error) {
    public List<AzureResponseChoice> getChoices() {
        return Objects.requireNonNullElse(choices, List.of());
    }

    public String getResponseText() {
        return getChoices().stream()
                .map(AzureResponseChoice::getMessage)
                .map(AzureResponseOutputContent::getContent)
                .reduce("", String::concat);
    }
}
