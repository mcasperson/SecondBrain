package secondbrain.infrastructure.gong;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GongCallExtensiveQuery(GongCallExtensiveQueryFiler filter) {
}
