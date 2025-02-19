package secondbrain.infrastructure.gong;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GongCallExtensiveContext(String system, List<GongCallExtensiveContextObject> objects) {
}
