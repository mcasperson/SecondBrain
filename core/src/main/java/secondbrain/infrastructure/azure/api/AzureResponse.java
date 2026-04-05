package secondbrain.infrastructure.azure.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.commons.collections4.CollectionUtils;

import java.util.List;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AzureResponse(List<AzureResponseChoice> choices,
                            List<AzureResponseOutput> output,
                            @JsonProperty("prompt_filter_results") List<AzureResponsePromptFilterResult> promptFilterResults,
                            AzureResponseError error,
                            Long created,
                            String id,
                            String model,
                            String object) {
    public List<AzureResponseChoice> getChoices() {
        return Objects.requireNonNullElse(choices, List.of());
    }
    public List<AzureResponseOutput> getOutput() {
        return Objects.requireNonNullElse(output, List.of());
    }

    public String getResponseText() {
        // This is the output for older API versions
        if (!CollectionUtils.isEmpty(getChoices())) {
            return getChoices().stream()
                    .map(AzureResponseChoice::getMessage)
                    .map(AzureResponseChoiceMessage::getContent)
                    .reduce("", String::concat);
        }

        // This is the output for newer API versions
        if (!CollectionUtils.isEmpty(getOutput())) {
            return getOutput().stream()
                    .flatMap(o -> o.content().stream())
                    .filter(o -> "output_text".equals(o.type()))
                    .map(AzureResponseOutputContent::text)
                    .reduce("", String::concat);
        }

        return "";
    }
}
