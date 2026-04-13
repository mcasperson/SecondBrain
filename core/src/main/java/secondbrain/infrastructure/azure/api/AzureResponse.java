package secondbrain.infrastructure.azure.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Objects;

/**
 * This record captures all the various outputs of all the API versions. We'll accept anything and abstract away the
 * differences with methods like getResponseText().
 */
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

        // The output might be in the text field
        if (!CollectionUtils.isEmpty(getOutput())) {
            final String result = getOutput().stream()
                    .map(AzureResponseOutput::text)
                    .filter(Objects::nonNull)
                    .reduce("", String::concat);
            if (StringUtils.isNotBlank(result)) {
                return result;
            }
        }

        // This used to be how we got results? Not sure now, as the text field above
        // might be the new way.
        if (!CollectionUtils.isEmpty(getOutput())) {
            return getOutput().stream()
                    .flatMap(o -> o.getContent().stream())
                    .filter(o -> "output_text".equals(o.type()))
                    .map(AzureResponseOutputContent::text)
                    .reduce("", String::concat);
        }

        return "";
    }
}
