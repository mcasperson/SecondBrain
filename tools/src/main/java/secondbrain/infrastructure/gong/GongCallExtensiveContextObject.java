package secondbrain.infrastructure.gong;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GongCallExtensiveContextObject(String objectType, String objectId) {
}
