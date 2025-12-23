package secondbrain.infrastructure.azure.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jspecify.annotations.Nullable;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AzureRequestMessage(String role, @Nullable String content) {
}
