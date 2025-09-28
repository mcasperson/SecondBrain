package secondbrain.infrastructure.azure.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AzureResponsePromptFilterResult(
        @JsonProperty("prompt_index") Integer promptIndex,
        @JsonProperty("content_filter_results") Map<String, AzureResponseContentFilterResult> contentFilterResults) {
}
