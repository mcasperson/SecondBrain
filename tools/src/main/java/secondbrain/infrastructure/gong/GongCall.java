package secondbrain.infrastructure.gong;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GongCall(String id, String title, String url) {
}
