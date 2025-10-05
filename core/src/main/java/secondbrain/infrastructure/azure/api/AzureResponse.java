package secondbrain.infrastructure.azure.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AzureResponse(List<AzureResponseChoice> choices,
                            @JsonProperty("prompt_filter_results") List<AzureResponsePromptFilterResult> promptFilterResults,
                            AzureResponseError error,
                            Long created, String id,
                            String model,
                            String object) {
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
