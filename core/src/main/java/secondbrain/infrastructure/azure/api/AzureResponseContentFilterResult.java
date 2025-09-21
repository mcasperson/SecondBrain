package secondbrain.infrastructure.azure.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AzureResponseContentFilterResult(Boolean filtered, String severity, Boolean detected) {
}
